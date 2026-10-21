package com.raspro.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

/** RasPro: user-controlled Android mock-location test app. */
class MainActivity : Activity(), OnMapReadyCallback {
    private val bg = Color.rgb(5, 10, 7)
    private val panel = Color.rgb(12, 23, 16)
    private val neon = Color.rgb(57, 255, 136)
    private val muted = Color.rgb(155, 190, 168)
    private lateinit var manager: LocationManager
    private lateinit var status: TextView
    private lateinit var latField: EditText
    private lateinit var lonField: EditText
    private lateinit var savedBox: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null
    private var selectedMarker: Marker? = null
    private var oneShotListener: LocationListener? = null
    private val locationTimeout = Handler(Looper.getMainLooper())
    private var locationTimeoutRunnable: Runnable? = null
    private var openMapAfterLocation = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var routeIndex = 0
    private var route: List<Pair<Double, Double>> = emptyList()
    private var routeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        if (requested.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(requested.toTypedArray(), 100)
        }
        buildUi()
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("RASPRO", 30, neon, true))
        content.addView(label("// MOCK LOCATION LAB  •  ANDROID", 12, muted, false))
        content.addView(spacer(14))
        status = label("STATUS: STOPPED\nSelect RasPro in Developer options → Select mock location app.", 13, neon, false)
        status.setPadding(dp(12), dp(12), dp(12), dp(12)); status.setBackgroundColor(panel)
        content.addView(status)
        content.addView(spacer(16))
        content.addView(label("LIVE GOOGLE MAP", 14, neon, true))
        content.addView(label("Tap any place on the map to select its coordinates. Map labels and road data come from Google Maps.", 12, muted, false))
        mapView = MapView(this)
        mapView.layoutParams = LinearLayout.LayoutParams(-1, dp(280)).apply { bottomMargin = dp(12) }
        content.addView(mapView)
        content.addView(button("CENTER MAP ON ENTERED COORDINATES", false) {
            val p = coords()
            if (p != null) moveMapTo(p.first, p.second, nameField.text.toString().trim().ifBlank { "Selected location" })
        })
        content.addView(spacer(8))
        content.addView(label("TARGET COORDINATES", 14, neon, true))
        latField = field("Latitude  (-90 to 90)", "23.8103")
        lonField = field("Longitude (-180 to 180)", "90.4125")
        content.addView(latField); content.addView(lonField)
        content.addView(button("FIND MY CURRENT LOCATION", false) { findMyCurrentLocation() })
        content.addView(button("GO TO MY LOCATION IN MAP", false) { findMyCurrentLocation(openMap = true) })
        content.addView(label("LOCATION NAME (OPTIONAL)", 12, muted, false))
        nameField = field("e.g. Home, Dhaka, Friend place", "")
        nameField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        content.addView(nameField)
        content.addView(button("SET MOCK LOCATION", true) { setCurrentLocation() })
        content.addView(button("STOP MOCK LOCATION", false) { stopMockLocation() })
        content.addView(button("SAVE THIS LOCATION", false) { saveLocation() })
        content.addView(spacer(12))
        content.addView(label("SAVED LOCATIONS", 14, neon, true))
        savedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(savedBox)
        content.addView(spacer(12))
        content.addView(label("ROUTE SIMULATION", 14, neon, true))
        content.addView(label("Add two or more saved points, then run the route. Points are replayed at a fixed interval for testing.", 12, muted, false))
        content.addView(button("START SAVED-POINT ROUTE", true) { startRoute() })
        content.addView(button("STOP ROUTE", false) { stopRoute() })
        content.addView(spacer(12))
        content.addView(label("DEVICE TROUBLESHOOTING", 14, neon, true))
        content.addView(label("Brand: ${Build.MANUFACTURER}\nModel: ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nBattery restrictions and mock-app selection can still stop location updates.", 12, muted, false))
        content.addView(button("OPEN RASPRO APP SETTINGS", false) { openAppSettings() })
        content.addView(button("OPEN DEVELOPER OPTIONS", false) { openDeveloperOptions() })
        content.addView(spacer(12))
        content.addView(label("This app uses Android's mock-location test provider. Other apps may ignore mock locations or detect them. Use only for testing apps/devices you control.", 11, muted, false))
        setContentView(root)
        refreshSaved()
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); typeface = Typeface.MONOSPACE
        if (bold) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(hintText: String, initial: String): EditText = EditText(this).apply {
        hint = hintText; setText(initial); setHintTextColor(muted); setTextColor(neon); textSize = 15f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setSingleLine(true); setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(panel)
        val lp = LinearLayout.LayoutParams(-1, dp(52)); lp.bottomMargin = dp(8); layoutParams = lp
    }

    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f; typeface = Typeface.MONOSPACE
        setTextColor(if (primary) bg else neon); setBackgroundColor(if (primary) neon else panel)
        val lp = LinearLayout.LayoutParams(-1, dp(48)); lp.bottomMargin = dp(8); layoutParams = lp
        setOnClickListener { action() }
    }

    private fun spacer(height: Int) = TextView(this).apply { height.let { layoutParams = LinearLayout.LayoutParams(1, dp(it)) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun coords(): Pair<Double, Double>? {
        val lat = latField.text.toString().trim().toDoubleOrNull()
        val lon = lonField.text.toString().trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            toast("Enter valid latitude and longitude."); return null
        }
        return lat to lon
    }

    /** One-tap current-location lookup. Prefer network location so an active GPS mock does not
     * automatically become the only source used for the lookup. Some devices may not provide it. */
    private fun findMyCurrentLocation(openMap: Boolean = false) {
        openMapAfterLocation = openMap
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
            toast("Allow location permission, then tap FIND MY CURRENT LOCATION again.")
            return
        }
        cleanupOneShotLocation()
        val providers = mutableListOf<String>()
        try { if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
        // GPS may be the mock provider while RasPro is active, so use it only as a fallback.
        try { if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        if (providers.isEmpty()) {
            toast("No location provider is enabled. Turn on Location services and try again.")
            return
        }
        // A recent network fix is the quickest route and is less likely to be the RasPro GPS mock.
        for (provider in providers) {
            try {
                val last = manager.getLastKnownLocation(provider)
                if (last != null && System.currentTimeMillis() - last.time < 120_000L &&
                    (provider == LocationManager.NETWORK_PROVIDER || !isMockSessionActive())) {
                    applyFoundLocation(last, openMapAfterLocation)
                    return
                }
            } catch (_: SecurityException) { }
            catch (_: Exception) { }
        }
        val candidates = providers.filter { it == LocationManager.NETWORK_PROVIDER || !isMockSessionActive() }
        if (candidates.isEmpty()) {
            toast("RasPro is already mocking GPS and no network fix is available. Stop mock GPS briefly to read the real GPS position.")
            return
        }
        status.text = "FINDING CURRENT LOCATION…\nWaiting for a device location fix."
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                cleanupOneShotLocation()
                applyFoundLocation(location, openMapAfterLocation)
            }
            @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, statusValue: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        oneShotListener = listener
        var registered = false
        for (provider in candidates) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) { }
            catch (_: Exception) { }
        }
        if (!registered) {
            cleanupOneShotLocation()
            toast("Could not start location lookup. Check location permission and device settings.")
            return
        }
        val timeout = Runnable {
            cleanupOneShotLocation()
            openMapAfterLocation = false
            status.text = "CURRENT LOCATION NOT FOUND\nTurn on Location services or move to an area with better signal, then retry."
            toast("Location lookup timed out. Try again outdoors or enable Wi-Fi/mobile data.")
        }
        locationTimeoutRunnable = timeout
        locationTimeout.postDelayed(timeout, 15_000L)
    }

    private fun applyFoundLocation(location: Location, openMap: Boolean) {
        latField.setText(String.format(Locale.US, "%.6f", location.latitude))
        lonField.setText(String.format(Locale.US, "%.6f", location.longitude))
        status.text = "CURRENT LOCATION FOUND\nLAT: ${String.format(Locale.US, "%.6f", location.latitude)}\nLON: ${String.format(Locale.US, "%.6f", location.longitude)}\nTap SAVE THIS LOCATION or SET MOCK LOCATION as needed."
        toast("Current coordinates filled in. You can save this point or use it.")
        if (openMap) {
            moveMapTo(location.latitude, location.longitude, "My current location")
        }
        openMapAfterLocation = false
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMapToolbarEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        val dhaka = LatLng(23.8103, 90.4125)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(dhaka, 12f))
        map.setOnMapClickListener { point ->
            latField.setText(String.format(Locale.US, "%.6f", point.latitude))
            lonField.setText(String.format(Locale.US, "%.6f", point.longitude))
            val title = nameField.text.toString().trim().ifBlank { "Selected map point" }
            selectedMarker?.remove()
            selectedMarker = map.addMarker(MarkerOptions().position(point).title(title))
            status.text = "MAP POINT SELECTED\nLAT: ${String.format(Locale.US, "%.6f", point.latitude)}\nLON: ${String.format(Locale.US, "%.6f", point.longitude)}\nSave this location or set it as mock location."
        }
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                map.isMyLocationEnabled = true
            }
        } catch (_: SecurityException) { }
    }

    private fun moveMapTo(lat: Double, lon: Double, title: String) {
        val point = LatLng(lat, lon)
        val map = googleMap
        if (map == null) {
            toast("Google Map is still loading. Please try again in a moment.")
            return
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
        selectedMarker?.remove()
        selectedMarker = map.addMarker(MarkerOptions().position(point).title(title))
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { if (::mapView.isInitialized) mapView.onPause(); super.onPause() }
    override fun onStop() { if (::mapView.isInitialized) mapView.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }

    private fun isMockSessionActive(): Boolean = getSharedPreferences("raspro", MODE_PRIVATE).getBoolean("mock_active", false)

    private fun cleanupOneShotLocation() {
        locationTimeoutRunnable?.let { locationTimeout.removeCallbacks(it) }
        locationTimeoutRunnable = null
        oneShotListener?.let { try { manager.removeUpdates(it) } catch (_: Exception) {} }
        oneShotListener = null
    }

    private fun setCurrentLocation() {
        val p = coords() ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100); return
        }
        try {
            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_SET_LOCATION
                putExtra(MockLocationService.EXTRA_LAT, p.first)
                putExtra(MockLocationService.EXTRA_LON, p.second)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            running = true
            status.text = "STATUS: STARTING MOCK LOCATION\nLAT: ${p.first}\nLON: ${p.second}\nKeep the ongoing RasPro notification visible."
            toast("Mock location service started. Check the ongoing notification.")
        } catch (e: SecurityException) {
            status.text = "PERMISSION REQUIRED\nEnable RasPro under Developer options → Select mock location app, then retry."
            toast("Choose RasPro as the mock-location app in Developer options.")
        } catch (e: Exception) { status.text = "ERROR: ${e.javaClass.simpleName}: ${e.message}" }
    }

    @Suppress("DEPRECATION")
    private fun ensureMockProvider() {
        try { manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE) }
        catch (_: IllegalArgumentException) { /* already exists */ }
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Suppress("DEPRECATION")
    private fun publish(lat: Double, lon: Double) {
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; accuracy = 3f; altitude = 0.0; speed = 0f; bearing = 0f
            time = System.currentTimeMillis()
            if (android.os.Build.VERSION.SDK_INT >= 17) elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
    }

    private fun stopMockLocation() {
        stopRoute()
        stopService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        running = false
        status.text = "STATUS: STOP REQUESTED\nRasPro service is stopping. Android may retain a cached last-known location briefly."
        toast("Mock location stop requested.")
    }

    private fun saveLocation() {
        val p = coords() ?: return
        val prefs = getSharedPreferences("raspro", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        val typedName = if (::nameField.isInitialized) nameField.text.toString().trim() else ""
        val pointName = typedName.ifBlank { "Point ${arr.length() + 1}" }
        // No distance threshold: any valid coordinate can be saved, including nearby points.
        arr.put(JSONObject().put("lat", p.first).put("lon", p.second).put("name", pointName))
        prefs.edit().putString("saved", arr.toString()).apply()
        refreshSaved(); toast("Saved '$pointName' — no distance restriction.")
    }

    private fun savedPoints(): List<Triple<String, Double, Double>> {
        val arr = try { JSONArray(getSharedPreferences("raspro", MODE_PRIVATE).getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN); val lon = o.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) null else Triple(o.optString("name", "Point ${i+1}"), lat, lon)
        }
    }

    private fun refreshSaved() {
        if (!::savedBox.isInitialized) return
        savedBox.removeAllViews()
        val points = savedPoints()
        if (points.isEmpty()) { savedBox.addView(label("No saved points yet.", 12, muted, false)); return }
        points.forEachIndexed { index, p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(4), dp(4)); setBackgroundColor(panel) }
            val info = label("${p.first}\n${p.second}, ${p.third}", 11, neon, false)
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val use = Button(this).apply { text = "USE"; setTextColor(neon); setBackgroundColor(bg); setOnClickListener { latField.setText(p.second.toString()); lonField.setText(p.third.toString()) } }
            row.addView(use)
            val del = Button(this).apply { text = "×"; setTextColor(neon); setBackgroundColor(bg); setOnClickListener { deleteSaved(index); refreshSaved() } }
            row.addView(del)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(5); savedBox.addView(row, lp)
        }
    }

    private fun deleteSaved(index: Int) {
        val prefs = getSharedPreferences("raspro", MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString("saved", "[]")) } catch (_: Exception) { JSONArray() }
        val next = JSONArray(); for (i in 0 until old.length()) if (i != index) next.put(old.get(i))
        prefs.edit().putString("saved", next.toString()).apply()
    }

    private fun startRoute() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100); return
        }
        val points = savedPoints()
        if (points.size < 2) { toast("Save at least two locations first."); return }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START_ROUTE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        running = true
        status.text = "STATUS: ROUTE SIMULATION STARTING\n${points.size} saved points • 3 seconds per point\nKeep the RasPro notification visible."
        toast("Background route simulation started.")
    }

    private fun stopRoute() {
        routeRunnable?.let { handler.removeCallbacks(it) }
        routeRunnable = null
        route = emptyList()
        if (running) startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP_ROUTE))
    }
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Open Android Settings → Apps → RasPro manually.")
        }
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            toast("Open Settings → Developer options manually.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    // Do not stop the foreground mock-location service when this screen is closed.
    override fun onDestroy() { cleanupOneShotLocation(); super.onDestroy() }
}
