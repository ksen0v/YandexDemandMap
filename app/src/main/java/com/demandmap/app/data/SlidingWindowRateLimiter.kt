package com.demandmap.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine port of sprostaxi_provider._SlidingWindowRateLimiter from the
 * FastAPI prototype. Blocks the caller until it's safe to send another
 * request without exceeding [maxCalls] sends in any rolling [periodMs]
 * window. This is the fix for bug #1 from the original report: a
 * concurrency cap alone doesn't protect you from sprostaxi.ru's real limit
 * ("Превышен лимит (10 нажатий за 10 секунд)"), because several small
 * concurrent batches back-to-back can still add up to more than 10 sends
 * inside one ten-second window. This tracks actual send timestamps instead.
 *
 * Shared across every call this client makes (all grid points, all
 * concurrent queries), because the server's limit is per device/IP over
 * time, not per logical request batch - and this app reuses one device_id
 * for its entire lifetime (see DeviceIdStore), same as the reference site.
 */
class SlidingWindowRateLimiter(private val maxCalls: Int, val periodMs: Long) {
    private val sentAt = ArrayDeque<Long>()
    private val mutex = Mutex()

    suspend fun acquire() {
        while (true) {
            var waitFor = 0L
            mutex.withLock {
                val now = System.currentTimeMillis()
                while (sentAt.isNotEmpty() && now - sentAt.first() >= periodMs) {
                    sentAt.removeFirst()
                }
                if (sentAt.size < maxCalls) {
                    sentAt.addLast(now)
                    return
                }
                waitFor = periodMs - (now - sentAt.first()) + 50
            }
            delay(maxOf(waitFor, 50))
        }
    }
}
