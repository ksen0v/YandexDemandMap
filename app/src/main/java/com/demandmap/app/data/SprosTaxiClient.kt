package com.demandmap.app.data

import android.util.Log
import com.demandmap.app.domain.DemandPoint
import com.demandmap.app.domain.ServiceType
import com.demandmap.app.domain.discGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct client for sprostaxi.ru's own /surge/update endpoint. No backend
 * server involved - this app talks to it straight from the device, the
 * same way sprostaxi.ru's own map.html frontend does.
 *
 * Confirmed against that frontend's own source (fetchSurgeForLocation() /
 * renderTariffs() in map.html), not guessed from captured traffic alone:
 *
 *   POST https://sprostaxi.ru/surge/update
 *   Content-Type: application/json
 *   Body: {"device_id": "web_xxxxxxxxxxxx", "lat": 55.78, "lon": 37.56}
 *
 * Response: an object keyed by tariff class, not a single coefficient:
 *   {"econom": {"surge": 1.2, "price": 240, "basePrice": 199}, "delivery": {...}, ...}
 * "delivery"/"courier" is the courier tariff; everything else is a taxi
 * tariff - there's no separate courier endpoint, see
 * [bestTariffForService] below.
 *
 * Rate limiting: the site's own 429 body says "Превышен лимит (10 нажатий
 * за 10 секунд)" - 10 requests per rolling 10-second window, not a
 * per-click concurrency cap. [SlidingWindowRateLimiter] enforces that
 * globally across every call this client makes.
 *
 * Etiquette: this is a small, free community tool with no visible auth
 * wall, not our infrastructure. Keep volume low regardless of how many
 * people install this app - the rate limiter, a modest concurrency cap,
 * and the TTL cache in DemandRepository all exist for that reason. Check
 * sprostaxi.ru's terms/robots.txt yourself before shipping this widely,
 * and don't remove the descriptive User-Agent suffix below.
 */
class SprosTaxiClient(
    private val deviceId: String,
    private val baseUrl: String = "https://sprostaxi.ru",
    private val endpointPath: String = "/surge/update",
    rateLimitMaxCalls: Int = 8,
    rateLimitPeriodMs: Long = 10_000L,
) {
    companion object {
        private const val TAG = "SprosTaxiClient"
        private const val MAX_CONCURRENT_REQUESTS = 3
        private const val MAX_FETCH_ATTEMPTS = 3

        // Russian + English variants, matching the reference frontend's own tariff table.
        private val COURIER_TARIFF_KEYS = setOf("доставка", "delivery", "courier")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val rateLimiter = SlidingWindowRateLimiter(rateLimitMaxCalls, rateLimitPeriodMs)
    private val concurrencyGate = Semaphore(MAX_CONCURRENT_REQUESTS)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 yandex-demand-map-android/0.1"

    /** Returns the parsed JSON body, or null to mean "got a 429, caller should retry". */
    private suspend fun trySend(lat: Double, lon: Double): JSONObject? {
        return concurrencyGate.withPermit {
            // Acquire the rate-limit slot inside the concurrency gate and right
            // before the actual send, so the recorded timestamp is as close as
            // possible to when the request truly goes out.
            rateLimiter.acquire()

            val requestBody = JSONObject().apply {
                put("device_id", deviceId)
                put("lat", lat)
                put("lon", lon)
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl$endpointPath")
                .post(requestBody)
                .header("Accept", "*/*")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/map.html")
                .header("User-Agent", userAgent)
                .build()

            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use { resp ->
                if (resp.code == 429) {
                    val retryAfterSec = resp.header("Retry-After")?.toDoubleOrNull()
                        ?: (rateLimiter.periodMs / 1000.0)
                    Log.w(TAG, "429 for ($lat, $lon); backing off ${retryAfterSec}s")
                    delay((retryAfterSec * 1000).toLong())
                    return@withPermit null
                }
                if (!resp.isSuccessful) {
                    throw IOException("sprostaxi HTTP ${resp.code}")
                }
                val text = resp.body?.string() ?: throw IOException("sprostaxi empty body")
                JSONObject(text)
            }
        }
    }

    private suspend fun fetchPoint(lat: Double, lon: Double): JSONObject {
        repeat(MAX_FETCH_ATTEMPTS) {
            trySend(lat, lon)?.let { return it }
        }
        throw IOException("sprostaxi rate limit not recovered after $MAX_FETCH_ATTEMPTS attempts")
    }

    /**
     * Same math as the reference frontend's per-tariff loop: prefer the
     * price/basePrice delta when present (the actual payout math),
     * otherwise derive a rough bonus from surge alone.
     */
    private fun tariffSurge(item: JSONObject): Pair<Double, Int> {
        var surge = if (item.has("surge") && !item.isNull("surge")) item.optDouble("surge", 1.0) else 1.0
        val price = if (item.has("price") && !item.isNull("price")) item.optDouble("price") else null
        val basePrice = if (item.has("basePrice") && !item.isNull("basePrice")) item.optDouble("basePrice") else null

        var bonus = 0.0
        if (price != null && basePrice != null && price > basePrice) {
            bonus = price - basePrice
            if (basePrice > 0) surge = maxOf(surge, price / basePrice)
        } else if (price != null && surge > 1) {
            val base = price / surge
            bonus = maxOf(0.0, price - base)
        }
        return surge to Math.round(bonus).toInt()
    }

    /**
     * Reduces a /surge/update response (one entry per tariff class) down to
     * a single (coefficient, bonusRub) for the requested service, taking
     * the hottest matching tariff at that point - mirrors the reference
     * frontend's own maxSurgeCoeff/maxSurgeRub reduction, just scoped to
     * taxi-only or courier-only keys instead of "every enabled tariff".
     */
    private fun bestTariffForService(payload: JSONObject, service: ServiceType): Pair<Double, Int> {
        var bestCoeff = 1.0
        var bestBonus = 0
        val keys = payload.keys()
        for (key in keys) {
            val item = payload.optJSONObject(key) ?: continue
            val isCourierKey = key.trim().lowercase() in COURIER_TARIFF_KEYS
            if (service == ServiceType.COURIER && !isCourierKey) continue
            if (service == ServiceType.TAXI && isCourierKey) continue

            val (coeff, bonus) = tariffSurge(item)
            if (coeff > bestCoeff) {
                bestCoeff = coeff
                bestBonus = bonus
            }
        }
        return bestCoeff to bestBonus
    }

    /**
     * The endpoint only takes a point, not a radius, so "radius" stays our
     * own concept: probe a small grid of points around the tap (see
     * [discGrid]) and call /surge/update once per point. Grid kept small
     * out of courtesy to sprostaxi's server - the rate limiter paces
     * requests regardless of grid size, but a smaller grid means a single
     * tap resolves inside one rate-limit window instead of spilling into a
     * second one.
     */
    suspend fun samplePoints(lat: Double, lon: Double, radiusM: Int, service: ServiceType): List<DemandPoint> =
        coroutineScope {
            val divisions = if (radiusM <= 1000) 3 else 4
            val grid = discGrid(lat, lon, radiusM, divisions)

            val deferred = grid.map { gp ->
                async {
                    try {
                        val payload = fetchPoint(gp.lat, gp.lon)
                        val (coeff, bonus) = bestTariffForService(payload, service)
                        DemandPoint(gp.lat, gp.lon, coeff, bonus, gp.distanceM)
                    } catch (e: Exception) {
                        Log.w(TAG, "point fetch failed for (${gp.lat}, ${gp.lon}): ${e.message}")
                        null
                    }
                }
            }

            val points = deferred.awaitAll().filterNotNull()
            if (points.isEmpty()) {
                throw IOException("sprostaxi /surge/update returned no usable points for this query")
            }
            points
        }
}
