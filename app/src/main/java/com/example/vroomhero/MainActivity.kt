package com.example.vroomhero

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vroomhero.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.POST

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var apiService: ApiService
    private var isGpsActive: Boolean = false
    private var lastSpeedLimit: Double? = null
    private var lastApiCallTime: Long = 0
    private var lastToastTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.speedNumberTextView.setTextColor(ContextCompat.getColor(this, R.color.retro_red))
            updateGpsIndicator()
            showToast("VroomHero Started! YAHTZEE!")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to inflate layout", e)
            showToast("UI initialization failed")
            finish()
            return
        }

        try {
            apiService = RetrofitClient.apiService
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize Retrofit", e)
            showToast("Network setup failed")
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        isGpsActive = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        updateGpsIndicator()
        requestLocationPermissions()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineLocationGranted && coarseLocationGranted) {
            startLocationUpdates()
        } else {
            showToast("Location permissions denied. Speedometer disabled.")
            binding.speedNumberTextView.text = "N/A"
            binding.speedUnitsTextView.text = ""
            binding.speedLimitTextView.text = "XX"
            binding.roadNameTextView?.text = ""
            isGpsActive = false
            updateGpsIndicator()
        }
    }

    private fun requestLocationPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fineLocation == PackageManager.PERMISSION_GRANTED && coarseLocation == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    10f,
                    this
                )
                isGpsActive = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                updateGpsIndicator()
            } else {
                Log.w("MainActivity", "Location permission not granted")
                isGpsActive = false
                updateGpsIndicator()
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Security exception in location updates", e)
            showToast("Location permission error")
            isGpsActive = false
            updateGpsIndicator()
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            val speedMph = location.speed * 2.23694 // m/s to MPH
            val formattedSpeed = String.format("%02d", speedMph.toInt() % 100) // Integer, padded to 2 digits
            binding.speedNumberTextView.text = formattedSpeed
            binding.speedUnitsTextView.text = "mph"
            isGpsActive = true
            updateGpsIndicator()

            val lat = location.latitude
            val lon = location.longitude
            val currentTime = System.currentTimeMillis()

            // Throttle API calls to every 90 seconds
            if (currentTime - lastApiCallTime < 90_000) {
                Log.d("VroomHero", "API call throttled, last call: ${(currentTime - lastApiCallTime)/1000}s ago")
                if (lastSpeedLimit != null) {
                    binding.speedLimitTextView.text = String.format("%.0f", lastSpeedLimit!!.toFloat())
                    showToast("Used last speed limit: $lastSpeedLimit mph")
                } else {
                    binding.speedLimitTextView.text = "XX"
                    binding.roadNameTextView?.text = ""
                    showToast("Waiting for speed limit")
                }
                return
            }

            // Fetch speed limit and road name from API
            lifecycleScope.launch {
                val (speedLimit, wayId, roadName) = fetchSpeedLimitFromOsm(lat, lon)
                Log.d("VroomHero", "Fetched speedLimit: $speedLimit, wayId: $wayId, roadName: $roadName")
                if (speedLimit != null && wayId != null) {
                    binding.speedLimitTextView.text = String.format("%.0f", speedLimit.toFloat())
                    binding.roadNameTextView?.text = roadName ?: "Unknown Road"
                    showToast("New speed limit: $speedLimit mph, road: ${roadName ?: "Unknown"}")
                    lastSpeedLimit = speedLimit
                } else {
                    binding.speedLimitTextView.text = "XX"
                    binding.roadNameTextView?.text = ""
                    showToast("No speed limit or road name found")
                    lastSpeedLimit = null
                }
                lastApiCallTime = currentTime
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing location update", e)
            showToast("Error updating speed: ${e.message}")
            binding.roadNameTextView?.text = ""
            isGpsActive = false
            updateGpsIndicator()
        }
    }

    suspend fun fetchSpeedLimitFromOsm(lat: Double, lon: Double): Triple<Double?, String?, String?> = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                showToast("Calling Overpass API")
            }
            val query = """
                [out:json][timeout:30];
                way(around:200,$lat,$lon)["highway"~"^(residential|primary|secondary|tertiary|motorway)$"]["maxspeed"];
                out tags;
            """.trimIndent()

            Log.d("VroomHero", "Sending Overpass query: $query")
            val response = apiService.getOsmData(query)
            Log.d("VroomHero", "Raw API response: $response")

            if (response.isEmpty()) {
                Log.e("VroomHero", "Empty response from Overpass API")
                withContext(Dispatchers.Main) {
                    showToast("API returned empty response")
                }
                return@withContext Triple(null, null, null)
            }

            val json = JSONObject(response)
            val elements = json.optJSONArray("elements") ?: run {
                Log.w("VroomHero", "No elements array in API response")
                withContext(Dispatchers.Main) {
                    showToast("No roads found in API response")
                }
                return@withContext Triple(null, null, null)
            }
            Log.d("VroomHero", "Elements array length: ${elements.length()}")

            if (elements.length() > 0) {
                val element = elements.getJSONObject(0)
                val tags = element.optJSONObject("tags") ?: run {
                    Log.w("VroomHero", "No tags in first element")
                    withContext(Dispatchers.Main) {
                        showToast("No tags in API response")
                    }
                    return@withContext Triple(null, null, null)
                }
                Log.d("VroomHero", "Tags: $tags")
                val maxSpeedStr = tags.optString("maxspeed")
                val roadName = tags.optString("name")
                Log.d("VroomHero", "Raw maxspeed value: $maxSpeedStr, roadName: $roadName")

                val maxSpeed = when {
                    maxSpeedStr.endsWith("mph", ignoreCase = true) -> {
                        maxSpeedStr.replace("[^0-9]".toRegex(), "").toDoubleOrNull()
                    }
                    maxSpeedStr.isNotEmpty() -> {
                        // Convert km/h to mph
                        maxSpeedStr.replace("[^0-9]".toRegex(), "").toDoubleOrNull()?.div(1.60934)
                    }
                    else -> null
                }

                if (maxSpeed != null) {
                    Log.d("VroomHero", "Parsed speed limit: $maxSpeed mph")
                    withContext(Dispatchers.Main) {
                        showToast("API success: Speed limit $maxSpeed mph")
                    }
                    val wayId = element.optString("id")
                    return@withContext Triple(maxSpeed, wayId, roadName.takeIf { it.isNotEmpty() })
                } else {
                    Log.w("VroomHero", "No valid maxspeed value in tags: $maxSpeedStr")
                    // Fallback for residential roads without maxspeed
                    if (tags.optString("highway") == "residential") {
                        Log.d("VroomHero", "Assuming default residential speed limit: 25 mph")
                        withContext(Dispatchers.Main) {
                            showToast("Default residential speed limit: 25 mph")
                        }
                        val wayId = element.optString("id")
                        return@withContext Triple(25.0, wayId, roadName.takeIf { it.isNotEmpty() })
                    }
                    withContext(Dispatchers.Main) {
                        showToast("No maxspeed tag in API response")
                    }
                }
            } else {
                Log.w("VroomHero", "No roads found in API response")
                withContext(Dispatchers.Main) {
                    showToast("No roads found in API response")
                }
            }
            Log.w("VroomHero", "No valid speed limit found in response")
            return@withContext Triple(null, null, null)
        } catch (e: Exception) {
            Log.e("VroomHero", "Error fetching speed limit: ${e.message}", e)
            withContext(Dispatchers.Main) {
                showToast("API error: ${e.message}")
            }
            return@withContext Triple(null, null, null)
        }
    }

    private fun updateGpsIndicator() {
        binding.gpsIndicator.setImageResource(
            if (isGpsActive) R.drawable.ic_gps_green else R.drawable.ic_gps_red
        )
    }

    private fun showToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 2000) { // 2-second debounce
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            lastToastTime = now
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            isGpsActive = true
            updateGpsIndicator()
            showToast("GPS enabled")
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            isGpsActive = false
            updateGpsIndicator()
            showToast("GPS disabled")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error removing location updates", e)
        }
    }
}

interface ApiService {
    @POST("interpreter")
    suspend fun getOsmData(@Body query: String): String
}