package com.demandmap.app.data

import android.content.Context

/**
 * SprosTaxiClient owns the sliding-window rate limiter and the device_id -
 * both need to be genuinely global for the whole app process, not one
 * instance per ViewModel. Otherwise the Activity and the background widget
 * Service would each enforce the 10-req/10s budget independently, and
 * together could still exceed sprostaxi.ru's real limit even though each
 * side individually thinks it's being polite.
 */
object AppServices {
    @Volatile private var repositoryInstance: DemandRepository? = null

    fun repository(context: Context): DemandRepository {
        return repositoryInstance ?: synchronized(this) {
            repositoryInstance ?: run {
                val deviceId = DeviceIdStore.getOrCreate(context.applicationContext)
                val client = SprosTaxiClient(deviceId = deviceId)
                DemandRepository(client).also { repositoryInstance = it }
            }
        }
    }
}
