package com.demandmap.app.data

import android.content.Context

/**
 * sprostaxi.ru's own frontend generates a device_id once and persists it in
 * localStorage: 'web_' + Date.now().toString(36) + 4 random base36 chars.
 * It's not validated against anything secret, just a stable per-client id -
 * so we don't need to be byte-identical to their generator, just the same
 * shape, and we persist it the same way (SharedPreferences instead of
 * localStorage) so it's stable across app restarts rather than regenerated
 * on every request.
 */
object DeviceIdStore {
    private const val PREFS_NAME = "demand_map_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private val chars = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val fresh = generate()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    private fun generate(): String {
        val body = (1..12).map { chars.random() }.joinToString("")
        return "web_$body"
    }
}
