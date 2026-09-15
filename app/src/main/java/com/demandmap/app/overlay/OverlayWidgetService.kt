package com.demandmap.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.demandmap.app.MainActivity
import com.demandmap.app.data.AppPreferences
import com.demandmap.app.data.AppServices
import com.demandmap.app.data.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot

/**
 * Draws a small floating "current coefficient" pill on top of other apps
 * and refreshes it on a timer. Runs as a foreground service (required on
 * modern Android for any long-running background work) with its own
 * dismissible notification.
 *
 * Etiquette note carried over from SprosTaxiClient: this polls
 * sprostaxi.ru on a schedule, which is meaningfully more sustained request
 * volume than tap-driven use of the main screen. [AppPreferences.MIN_WIDGET_INTERVAL_SEC]
 * is a hard floor (10s, matching the site's own stated limit), not just a
 * suggested default - see the interval slider's copy in MapScreen. It also
 * queries a tiny radius (see WIDGET_QUERY_RADIUS_M) so a tick is
 * ordinarily a single request, not a whole grid, and shares the same
 * globally-rate-limited client as the rest of the app via AppServices.
 */
class OverlayWidgetService : Service() {

    companion object {
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        const val ACTION_STOP = "com.demandmap.app.overlay.ACTION_STOP"
        private const val CHANNEL_ID = "demand_widget_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WIDGET_QUERY_RADIUS_M = 30

        // The UI (MapScreen's toggle) reads this instead of tracking its own
        // "did I just press start" flag - a Service outlives the Activity
        // that started it (that's the point of a foreground service), so a
        // locally-remembered toggle goes stale the moment the app is closed
        // and reopened while the widget is still running. This is the
        // actual source of truth, set only from the service's own
        // lifecycle methods below.
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var textView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var intervalMs: Long = AppPreferences.DEFAULT_WIDGET_INTERVAL_SEC * 1000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val intervalSec = (intent?.getIntExtra(EXTRA_INTERVAL_SEC, AppPreferences.DEFAULT_WIDGET_INTERVAL_SEC)
            ?: AppPreferences.DEFAULT_WIDGET_INTERVAL_SEC)
            .coerceAtLeast(AppPreferences.MIN_WIDGET_INTERVAL_SEC)
        intervalMs = intervalSec * 1000L

        startForeground(NOTIFICATION_ID, buildNotification())
        if (overlayView == null) addOverlayView()
        startPolling()
        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        pollingJob?.cancel()
        serviceScope.cancel()
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
        super.onDestroy()
    }

    // --- Notification -------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Виджет коэффициента", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val stopIntent = Intent(this, OverlayWidgetService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, pendingFlags)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KsenovКэф")
            .setContentText("Виджет коэффициента активен")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPending)
            .addAction(0, "Остановить", stopPending)
            .setOngoing(true)
            .build()
    }

    // --- Floating view --------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlayView() {
        val density = resources.displayMetrics.density
        val paddingH = (14 * density).toInt()
        val paddingV = (8 * density).toInt()

        val view = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.argb(230, 74, 20, 140)) // deep purple pill, matches the map gradient
            }
        }
        textView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        // Minimal drag-to-move + tap-to-open-app, no extra libraries.
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        view.setOnTouchListener { touchedView, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - downRawX).toInt()
                    params.y = initialY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(touchedView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())
                    if (moved < 12) {
                        val openIntent = Intent(this@OverlayWidgetService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(openIntent)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(view, params) }
        overlayView = view
    }

    // --- Polling loop ---------------------------------------------------

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    private suspend fun tick() {
        if (!LocationHelper.hasPermission(this)) {
            showError()
            return
        }
        val location = LocationHelper.getCurrentLocation(this) ?: run {
            showError()
            return
        }

        val service = AppPreferences.getServiceType(this)
        try {
            val (point, _) = AppServices.repository(this).samplePoints(
                location.latitude, location.longitude, WIDGET_QUERY_RADIUS_M, service,
            )
            showSuccess("x${String.format(Locale.US, "%.2f", point.coefficient)}")
        } catch (e: Exception) {
            showError()
        }
    }

    /** Back to the normal purple pill with the latest coefficient. */
    private fun showSuccess(text: String) {
        mainHandler.post {
            textView?.text = text
            (textView?.background as? GradientDrawable)?.setColor(Color.argb(230, 74, 20, 140))
        }
    }

    /**
     * A failed tick (no permission, no GPS fix, network/rate-limit error)
     * turns the pill red with a dashed placeholder instead of quietly
     * keeping the last successful number on screen, which could otherwise
     * read as still-current data.
     */
    private fun showError() {
        mainHandler.post {
            textView?.text = "--"
            (textView?.background as? GradientDrawable)?.setColor(Color.argb(230, 217, 72, 59))
        }
    }
}
