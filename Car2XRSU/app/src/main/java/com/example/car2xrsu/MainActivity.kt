package com.example.car2xrsu

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvRsuLocation: TextView
    private lateinit var tvLastVehicle: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var mapView: MapView

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var rsuLat: Double? = null
    private var rsuLon: Double? = null

    // RSU stability filter
    private val rsuFilter = RsuLocationFilter(
        accuracyGateMeters = 25f,
        alpha = 0.15,
        minUpdateDistanceMeters = 1.5
    )

    // UDP
    private val camPort = 30001
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null

    // Map overlays
    private var rsuMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var dangerCircle: Polygon? = null
    private var criticalCircle: Polygon? = null

    // Multi-vehicle data
    data class VehicleState(
        val vehicleId: String,
        var lat: Double,
        var lon: Double,
        var speed: Double,
        var ip: String,
        var denmPort: Int,
        var lastSeen: Long
    )

    private val vehicles = HashMap<String, VehicleState>()
    private val vehicleMarkers = HashMap<String, Marker>()
    private val VEHICLE_TIMEOUT_MS = 10_000L

    // Map state
    private var mapCenteredOnce = false

    // Log list for CSV
    private val logList = mutableListOf<String>()

    // Permissions
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) startRsu()
            else tvRsuLocation.text = "RSU Location: permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )

        setContentView(R.layout.activity_main)

        tvRsuLocation = findViewById(R.id.tvRsuLocation)
        tvLastVehicle = findViewById(R.id.tvLastVehicle)
        tvWarning = findViewById(R.id.tvWarning)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        mapView = findViewById(R.id.osmMap)

        setupMap()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            100L
        )
            .setMinUpdateIntervalMillis(100L)
            .setMaxUpdateDelayMillis(100L)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateAgeMillis(300L)
            .build()

        btnStart.setOnClickListener {
            if (listeningJob == null) checkPermissionsAndStart()
        }

        btnStop.setOnClickListener { stopListening() }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setUseDataConnection(true)
        mapView.setMinZoomLevel(5.0)
        mapView.setMaxZoomLevel(20.0)
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun checkPermissionsAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startRsu()
        }
    }

    private fun startRsu() {
        startLocationUpdates()
        startListeningForCamOnce()
        tvRsuLocation.text = "RSU Location: acquiring GPS..."
        tvWarning.text = "Listening for CAM beacons..."
    }

    // ---------- RSU GPS ----------
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            rsuLocationCallback,
            mainLooper
        )
    }

    private val rsuLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val best = result.locations
                .filter { it.accuracy > 0f }
                .sortedWith(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
                .firstOrNull() ?: result.lastLocation ?: return

            val filtered = rsuFilter.update(best) ?: run {
                tvRsuLocation.text = "RSU GPS filtered (${best.accuracy}m)..."
                return
            }

            rsuLat = filtered.latitude
            rsuLon = filtered.longitude

            tvRsuLocation.text =
                "RSU GPS filtered (${"%.1f".format(filtered.accuracy)}m)\nLat=${filtered.latitude}, Lon=${filtered.longitude}"

            updateRsuMarker(filtered)
        }
    }

    private fun updateDangerCircles(lat: Double, lon: Double) {
        val center = GeoPoint(lat, lon)

        // Danger Zone (5m)
        if (dangerCircle == null) {
            dangerCircle = Polygon().apply {
                fillPaint.color = android.graphics.Color.argb(60, 255, 0, 0)
                outlinePaint.color = android.graphics.Color.GREEN
                outlinePaint.strokeWidth = 4f
            }
            mapView.overlays.add(dangerCircle)
        }
        dangerCircle!!.points = Polygon.pointsAsCircle(center, 5.0)

        // Critical Zone (1m)
        if (criticalCircle == null) {
            criticalCircle = Polygon().apply {
                fillPaint.color = android.graphics.Color.argb(120, 200, 0, 0)
                outlinePaint.color = android.graphics.Color.RED
                outlinePaint.strokeWidth = 4f
            }
            mapView.overlays.add(criticalCircle)
        }
        criticalCircle!!.points = Polygon.pointsAsCircle(center, 1.0)

        mapView.invalidate()
    }

    private fun updateRsuMarker(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)

        if (rsuMarker == null) {
            rsuMarker = Marker(mapView).apply {
                position = point
                title = "RSU"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = resources.getDrawable(org.osmdroid.library.R.drawable.marker_default, null)
                icon.mutate().setTint(android.graphics.Color.BLUE)
            }
            mapView.overlays.add(rsuMarker)
        } else {
            rsuMarker!!.position = point
        }

        if (!mapCenteredOnce) {
            mapView.controller.setZoom(17.0)
            mapView.controller.setCenter(point)
            mapCenteredOnce = true
        }

        updateDangerCircles(location.latitude, location.longitude)
        mapView.invalidate()
    }

    // ---------- CAM LISTENER ----------
    private fun startListeningForCamOnce() {
        if (listeningJob != null) return

        listeningJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(camPort).apply {
                    soTimeout = 1500
                }
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        handleCam(msg)
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvWarning.text = "Error listening: ${e.message}"
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------- LOG HELPER ----------
    private fun addLog(
        vehicleId: String,
        speed: Double,
        distance: Double,
        warning: String,
        denmSent: Boolean
    ) {
        val timestamp = System.currentTimeMillis()
        val safeWarning = warning.replace(",", " ")
        val entry = "$timestamp,$vehicleId,$speed,$distance,$safeWarning,$denmSent"
        logList.add(entry)
    }

    private suspend fun handleCam(jsonString: String) {
        try {
            val json = JSONObject(jsonString)

            val vehicleId = json.getString("vehicle_id")
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            val speed = json.getDouble("speed_kmh")
            val vehicleIp = json.getString("ip")
            val vehicleDenmPort = json.getInt("denm_port")

            // --- MULTI VEHICLE STORE ---
            val now = System.currentTimeMillis()
            val st = vehicles[vehicleId]
            if (st == null) {
                vehicles[vehicleId] = VehicleState(
                    vehicleId = vehicleId,
                    lat = lat,
                    lon = lon,
                    speed = speed,
                    ip = vehicleIp,
                    denmPort = vehicleDenmPort,
                    lastSeen = now
                )
            } else {
                st.lat = lat
                st.lon = lon
                st.speed = speed
                st.ip = vehicleIp
                st.denmPort = vehicleDenmPort
                st.lastSeen = now
            }

            cleanupStaleVehicles()

            val distance = computeDistance(lat, lon)
            val warning = buildWarningText(speed, distance)

            addLog(vehicleId, speed, distance, warning, warning != "No danger.")

            withContext(Dispatchers.Main) {
                tvLastVehicle.text =
                    "Last vehicle: $vehicleId | Dist=${"%.2f".format(distance)} m | Speed=${"%.1f".format(speed)} km/h"
                tvWarning.text = "Warning: $warning"

                updateVehicleMarker(vehicleId, lat, lon)
                updateRouteLine(lat, lon) // line to LAST received vehicle
            }

            if (warning != "No danger.") {
                sendDenmToVehicle(vehicleIp, vehicleDenmPort, distance, speed, warning)
            }

            // --- V2V RELAY (send each vehicle list of other vehicles) ---
            sendV2VUpdates()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupStaleVehicles() {
        val now = System.currentTimeMillis()
        val it = vehicles.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val vid = entry.key
            val st = entry.value
            if (now - st.lastSeen > VEHICLE_TIMEOUT_MS) {
                vehicleMarkers[vid]?.let { m -> mapView.overlays.remove(m) }
                vehicleMarkers.remove(vid)
                it.remove()
            }
        }
    }

    // ---------- V2V RELAY ----------
    private fun sendV2VUpdates() {
        val snapshot = vehicles.values.toList()

        snapshot.forEach { target ->
            val others = snapshot.filter { it.vehicleId != target.vehicleId }

            val payload = JSONObject().apply {
                put("type", "V2V")
                put("from", "RSU01")
                put("timestamp", System.currentTimeMillis())
                val arr = JSONArray()
                others.forEach { v ->
                    arr.put(JSONObject().apply {
                        put("vehicle_id", v.vehicleId)
                        put("lat", v.lat)
                        put("lon", v.lon)
                        put("speed_kmh", v.speed)
                        put("lastSeenMs", v.lastSeen)
                    })
                }
                put("vehicles", arr)
            }

            val data = payload.toString().toByteArray()

            scope.launch {
                try {
                    val packet = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName(target.ip),
                        target.denmPort
                    )
                    DatagramSocket().use { it.send(packet) }
                } catch (_: Exception) { }
            }
        }
    }

    private fun computeDistance(vehLat: Double, vehLon: Double): Double {
        val rLat = rsuLat ?: return Double.MAX_VALUE
        val rLon = rsuLon ?: return Double.MAX_VALUE

        val res = FloatArray(1)
        Location.distanceBetween(rLat, rLon, vehLat, vehLon, res)
        return res[0].toDouble()
    }

    // ---------- WARNING LOGIC ----------
    private fun buildWarningText(speed: Double, distance: Double?): String {
        val d = distance ?: Double.MAX_VALUE
        val warningDistance = 7.0
        val criticalDistance = 5.0
        val speedLimit = 30.0

        val overspeed = speed > speedLimit
        val close = d <= warningDistance
        val critical = d <= criticalDistance

        return when {
            critical && overspeed -> "⚠ COLLISION RISK (VERY CLOSE & OVERSPEED)!"
            critical -> "⚠ COLLISION RISK! Vehicle extremely close (<5m)."
            close && overspeed -> "Vehicle close (<7m) and overspeed!"
            close -> "Vehicle approaching RSU (1–7m)."
            else -> "No danger."
        }
    }

    // ---------- MAP: MULTI VEHICLE MARKERS ----------
    private fun updateVehicleMarker(vehicleId: String, lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)

        val marker = vehicleMarkers[vehicleId]
        if (marker == null) {
            val m = Marker(mapView).apply {
                position = point
                title = vehicleId
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            vehicleMarkers[vehicleId] = m
            mapView.overlays.add(m)
        } else {
            smoothMoveMarker(marker, lat, lon)
            marker.title = vehicleId
        }

        mapView.invalidate()
    }

    private fun smoothMoveMarker(marker: Marker, newLat: Double, newLon: Double) {
        val start = marker.position
        val end = GeoPoint(newLat, newLon)
        val steps = 10

        CoroutineScope(Dispatchers.Main).launch {
            for (i in 1..steps) {
                val lat = start.latitude + (end.latitude - start.latitude) * i / steps
                val lon = start.longitude + (end.longitude - start.longitude) * i / steps
                marker.position = GeoPoint(lat, lon)
                mapView.invalidate()
                delay(20)
            }
        }
    }

    private fun updateRouteLine(vehLat: Double, vehLon: Double) {
        val rLat = rsuLat ?: return
        val rLon = rsuLon ?: return

        val rsuPoint = GeoPoint(rLat, rLon)
        val vehPoint = GeoPoint(vehLat, vehLon)

        if (routeLine == null) {
            routeLine = Polyline().apply {
                outlinePaint.color = android.graphics.Color.RED
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(routeLine)
        }

        routeLine!!.setPoints(listOf(rsuPoint, vehPoint))
        mapView.invalidate()
    }

    // ---------- DENM SENDER ----------
    private fun sendDenmToVehicle(
        ip: String,
        port: Int,
        distance: Double,
        speed: Double,
        warningText: String
    ) {
        scope.launch {
            try {
                val json = """
                {
                  "type": "DENM",
                  "event": "dangerous_situation",
                  "cause": "$warningText",
                  "severity": "high",
                  "distance_m": $distance,
                  "speed_kmh": $speed,
                  "timestamp": ${System.currentTimeMillis()},
                  "rsuId": "RSU01"
                }
                """.trimIndent()

                val data = json.toByteArray()
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(ip),
                    port
                )

                DatagramSocket().use { socket ->
                    socket.send(packet)
                }

                addLog("RSU01", speed, distance, "DENM: $warningText", true)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvWarning.text = "DENM send error: ${e.message}"
                }
            }
        }
    }

    // ---------- CSV EXPORT ----------
    private fun exportCsv() {
        try {
            if (logList.isEmpty()) {
                tvWarning.text = "No logs to export."
                return
            }

            val header = "timestamp,vehicle_id,speed_kmh,distance_m,warning,denm_sent\n"
            val csvContent = StringBuilder(header)
            logList.forEach { csvContent.append(it).append("\n") }

            val fileName = "Car2X_Logs_${System.currentTimeMillis()}.csv"

            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/RSU_APP")
            }

            val uri = contentResolver.insert(
                android.provider.MediaStore.Files.getContentUri("external"),
                values
            )

            if (uri == null) {
                tvWarning.text = "Failed to create file"
                return
            }

            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(csvContent.toString().toByteArray())
            }

            tvWarning.text = "CSV exported to:\nDocuments/RSU_APP/$fileName"

        } catch (e: Exception) {
            tvWarning.text = "CSV export failed: ${e.message}"
        }
    }

    // ---------- STOP ----------
    private fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null

        fusedLocationClient.removeLocationUpdates(rsuLocationCallback)

        tvWarning.text = "Stopped. Exporting CSV..."
        exportCsv()

        // reset filter
        rsuFilter.reset()
        mapCenteredOnce = false

        // clear vehicles + markers on stop
        vehicles.clear()
        vehicleMarkers.values.forEach { mapView.overlays.remove(it) }
        vehicleMarkers.clear()
        routeLine?.let { mapView.overlays.remove(it) }
        routeLine = null
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        scope.cancel()
    }

    /**
     * RSU location stability:
     * - Gate poor accuracy
     * - Smooth with Exponential Moving Average (EMA)
     * - Ignore micro-jitter updates
     */
    private class RsuLocationFilter(
        private val accuracyGateMeters: Float,
        private val alpha: Double,
        private val minUpdateDistanceMeters: Double
    ) {
        private var hasState = false
        private var latEma = 0.0
        private var lonEma = 0.0
        private var lastAccepted: Location? = null

        fun reset() {
            hasState = false
            latEma = 0.0
            lonEma = 0.0
            lastAccepted = null
        }

        fun update(newLoc: Location): Location? {
            if (newLoc.accuracy > accuracyGateMeters) return null

            if (!hasState) {
                hasState = true
                latEma = newLoc.latitude
                lonEma = newLoc.longitude
                lastAccepted = Location(newLoc)
                return Location(newLoc)
            }

            val prev = lastAccepted ?: return null
            val dist = distanceMeters(prev.latitude, prev.longitude, newLoc.latitude, newLoc.longitude)
            val accuracyNotBetter = newLoc.accuracy >= prev.accuracy - 1f
            if (dist < minUpdateDistanceMeters && accuracyNotBetter) return null

            latEma = latEma + alpha * (newLoc.latitude - latEma)
            lonEma = lonEma + alpha * (newLoc.longitude - lonEma)

            val out = Location(newLoc).apply {
                latitude = latEma
                longitude = lonEma
            }

            lastAccepted = Location(out)
            return out
        }

        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val res = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, res)
            return res[0].toDouble()
        }
    }
}
