package com.raspro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import java.util.Locale

/** User-visible foreground service that maintains an explicitly enabled mock location. */
class MockLocationService : Service() {
    companion object {
        const val ACTION_SET_LOCATION = "com.raspro.app.SET_LOCATION"
        const val ACTION_START_ROUTE = "com.raspro.app.START_ROUTE"
        const val ACTION_STOP_ROUTE = "com.raspro.app.STOP_ROUTE"
        const val ACTION_STOP = "com.raspro.app.STOP"
        const val EXTRA_LAT = "latitude"
        const val EXTRA_LON = "longitude"
        private const val CHANNEL_ID = "raspro_mock_location"
        private const val NOTIFICATION_ID = 4017
        private const val UPDATE_MS = 3000L
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var routeMode = false
    private var routePoints: List<Pair<Double, Double>> = emptyList()
    private var routeIndex = 0
    private var currentLat = 23.8103
    private var currentLon = 90.4125

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMocking(clearSavedState = true); stopSelf(); return START_NOT_STICKY }
            ACTION_STOP_ROUTE -> {
                routeMode = false
                routePoints = emptyList()
                persistState()
                updateNotification("Mock location active")
                return START_STICKY
            }
            ACTION_START_ROUTE -> {
                // Enter foreground immediately; this avoids the foreground-service startup timeout.
                startForeground(NOTIFICATION_ID, notification("Starting route simulation"))
                val points = readSavedPoints()
                if (points.size < 2) {
                    updateNotification("Save at least two locations, then start the route")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                routePoints = points
                routeIndex = 0
                routeMode = true
                try {
                    active = true
                    ensureProvider()
                    publishNext()
                    scheduleUpdate()
                    persistState()
                } catch (e: SecurityException) {
                    active = false
                    updateNotification("Mock permission missing · choose RasPro in Developer options")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                } catch (e: Exception) {
                    active = false
                    updateNotification("Provider error · reopen RasPro")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                }
            }
            ACTION_SET_LOCATION -> {
                // A foreground service must promote itself promptly on Android 8+.
                startForeground(NOTIFICATION_ID, notification("Starting mock location"))
                currentLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                currentLon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                if (!currentLat.isFinite() || !currentLon.isFinite() || currentLat !in -90.0..90.0 || currentLon !in -180.0..180.0) {
                    stopMocking(clearSavedState = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                routeMode = false
                routePoints = emptyList()
                persistState()
                try {
                    active = true
                    ensureProvider()
                    publish(currentLat, currentLon)
                    scheduleUpdate()
                    persistState()
                } catch (e: SecurityException) {
                    active = false
                    updateNotification("Mock permission missing · choose RasPro in Developer options")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                } catch (e: Exception) {
                    active = false
                    updateNotification("Provider error · reopen RasPro")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                }
            }
            null -> {
                // START_STICKY may restart the service after process reclamation. Restore only
                // a session the user explicitly started; force-stop still prevents restart.
                val prefs = getSharedPreferences("raspro", MODE_PRIVATE)
                if (!prefs.getBoolean("mock_active", false)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentLat = prefs.getString("active_lat", "23.8103")?.toDoubleOrNull() ?: 23.8103
                currentLon = prefs.getString("active_lon", "90.4125")?.toDoubleOrNull() ?: 90.4125
                routeMode = prefs.getBoolean("route_active", false)
                if (routeMode) routePoints = readSavedPoints()
                startForeground(NOTIFICATION_ID, notification(if (routeMode) "Restoring route simulation" else "Restoring mock location"))
                try {
                    active = true
                    ensureProvider()
                    if (routeMode && routePoints.size < 2) routeMode = false
                    publishNext()
                    scheduleUpdate()
                    persistState()
                } catch (e: Exception) {
                    updateNotification("Could not restore location · open RasPro and retry")
                    stopMocking(clearSavedState = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> if (!active) { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun ensureProvider() {
        @Suppress("DEPRECATION")
        try {
            locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        } catch (_: IllegalArgumentException) {
            // Provider may already exist because this service is restarting.
        }
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun publish(lat: Double, lon: Double) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
            altitude = 0.0
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        updateNotification(if (routeMode) "Route simulation active" else "Mock location active · ${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lon)}")
    }

    private fun publishNext() {
        if (routeMode && routePoints.isNotEmpty()) {
            val point = routePoints[routeIndex % routePoints.size]
            currentLat = point.first
            currentLon = point.second
            routeIndex++
        }
        publish(currentLat, currentLon)
    }

    private val updater = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                publishNext()
                handler.postDelayed(this, UPDATE_MS)
            } catch (_: SecurityException) {
                updateNotification("Mock permission lost · open RasPro")
                stopMocking()
                stopSelf()
            } catch (_: Exception) {
                updateNotification("Location update error · open RasPro")
                handler.postDelayed(this, UPDATE_MS)
            }
        }
    }

    private fun scheduleUpdate() {
        handler.removeCallbacks(updater)
        handler.postDelayed(updater, UPDATE_MS)
    }

    private fun readSavedPoints(): List<Pair<Double, Double>> {
        val json = getSharedPreferences("raspro", MODE_PRIVATE).getString("saved", "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RasPro Mock Location", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when RasPro is actively providing a mock location"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("RasPro · Mock GPS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun persistState() {
        getSharedPreferences("raspro", MODE_PRIVATE).edit()
            .putBoolean("mock_active", active)
            .putBoolean("route_active", active && routeMode)
            .putString("active_lat", currentLat.toString())
            .putString("active_lon", currentLon.toString())
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun stopMocking(clearSavedState: Boolean = false) {
        active = false
        if (clearSavedState) {
            getSharedPreferences("raspro", MODE_PRIVATE).edit()
                .putBoolean("mock_active", false)
                .putBoolean("route_active", false)
                .apply()
        }
        routeMode = false
        handler.removeCallbacks(updater)
        try { locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
