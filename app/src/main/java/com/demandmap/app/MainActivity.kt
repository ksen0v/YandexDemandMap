package com.demandmap.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.demandmap.app.ui.AppRoot
import com.demandmap.app.ui.theme.DemandMapTheme
import org.osmdroid.config.Configuration
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid wants its own SharedPreferences + a descriptive user-agent
        // (map tile servers rate-limit/block on a missing or generic UA).
        val osmPrefs = applicationContext.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        Configuration.getInstance().load(applicationContext, osmPrefs)
        Configuration.getInstance().userAgentValue = applicationContext.packageName

        // Explicit, persistent tile cache - without this osmdroid still
        // caches to disk, but the defaults can be smaller/less predictable
        // than we want for "don't keep re-downloading my city". Using
        // app-specific external storage (no permission needed on modern
        // Android) rather than the internal cache dir, since the OS can
        // silently clear cacheDir under storage pressure but leaves
        // app-specific external files alone until uninstall/"clear data".
        val osmBasePath = File(
            applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir,
            "osmdroid",
        )
        val osmTileCache = File(osmBasePath, "tiles")
        osmTileCache.mkdirs()
        Configuration.getInstance().osmdroidBasePath = osmBasePath
        Configuration.getInstance().osmdroidTileCache = osmTileCache
        // ~300MB is comfortably enough for a city's worth of tiles across
        // the zoom levels this app actually uses (13-15).
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        // Map tiles barely change - treat cached ones as fresh for a month
        // instead of re-checking/re-fetching on every use.
        Configuration.getInstance().expirationOverrideDuration = TimeUnit.DAYS.toMillis(30)

        setContent {
            DemandMapTheme {
                AppRoot()
            }
        }
    }
}
