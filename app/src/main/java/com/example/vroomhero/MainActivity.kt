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
import retrofit2.http.Body
import retrofit2.http.POST
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var database: AppDatabase
    private lateinit var apiService: ApiService
    private var currentRoadId: String? = null
    private var isGpsActive: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.speedNumberTextView.setTextColor(ContextCompat.getColor(this, R.color.retro_red))
            updateGpsIndicator()
            Toast.makeText(this, "VroomHero Started! YAHTZEE!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to inflate layout", e)
            Toast.makeText(this, "UI initialization failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            database = AppDatabase.getDatabase(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize database", e)
            Toast.makeText(this, "Database initialization failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            apiService = RetrofitClient.apiService
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize Retrofit", e)
            Toast.makeText(this, "Network setup failed", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Location permissions denied. Speedometer disabled.", Toast.LENGTH_LONG).show()
            binding.speedNumberTextView.text = "N/A"
            binding.speedUnitsTextView.text = ""
            binding.speedLimitTextView.text = "XX"
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
            Toast.makeText(this, "Location permission error", Toast.LENGTH_SHORT).show()
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
            val newRoadId = "way_${UUID.randomUUID().toString().substring(0, 8)}" // Temporary UUID
            if (newRoadId != currentRoadId) {
                currentRoadId = newRoadId
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Checking database for speed limit", Toast.LENGTH_SHORT).show()
                    val cachedSpeedLimit = database.speedLimitDao().getSpeedLimit(newRoadId)
                    if (cachedSpeedLimit != null) {
                        binding.speedLimitTextView.text = String.format("%.0f", cachedSpeedLimit.speedLimit.toFloat())
                        Toast.makeText(this@MainActivity, "Found cached speed limit: ${cachedSpeedLimit.speedLimit} km/h", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No cached speed limit, fetching from API", Toast.LENGTH_SHORT).show()
                        val (speedLimit, wayId) = fetchSpeedLimitFromOsm(lat, lon)
                        if (speedLimit != null && wayId != null) {
                            val roadId = "way_$wayId"
                            val cachedSpeedLimit = database.speedLimitDao().getSpeedLimit(roadId)
                            if (cachedSpeedLimit == null) {
                                database.speedLimitDao().insert(
                                    SpeedLimitEntity(roadId = roadId, speedLimit = speedLimit)
                                )
                                Toast.makeText(this@MainActivity, "Saved new speed limit: $speedLimit mph", Toast.LENGTH_SHORT).show()
                            }
                            binding.speedLimitTextView.text = String.format("%.0f", speedLimit.toFloat())
                        } else {
                            binding.speedLimitTextView.text = "XX"
                            Toast.makeText(this@MainActivity, "No speed limit found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing location update", e)
            Toast.makeText(this, "Error updating speed: ${e.message}", Toast.LENGTH_SHORT).show()
            isGpsActive = false
            updateGpsIndicator()
        }
    }

    suspend fun fetchSpeedLimitFromOsm(lat: Double, lon: Double): Pair<Int?, String?> = withContext(Dispatchers.IO) {        try {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Calling Overpass API", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@MainActivity, "API returned empty response", Toast.LENGTH_SHORT).show()
                }
                return@withContext Pair(null, null)
            }

            val json = JSONObject(response)
            val elements = json.optJSONArray("elements") ?: run {
                Log.w("VroomHero", "No elements array in API response")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No roads found in API response", Toast.LENGTH_SHORT).show()
                }
                return@withContext Pair(null, null)
            }
            Log.d("VroomHero", "Elements array length: ${elements.length()}")

            if (elements.length() > 0) {
                val element = elements.getJSONObject(0)
                val tags = element.optJSONObject("tags") ?: run {
                    Log.w("VroomHero", "No tags in first element")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No tags in API response", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext Pair(null, null)
                }
                Log.d("VroomHero", "Tags: $tags")
                val maxSpeedStr = tags.optString("maxspeed")
                Log.d("VroomHero", "Raw maxspeed value: $maxSpeedStr")

                val maxSpeed = when {
                    maxSpeedStr.endsWith("mph", ignoreCase = true) -> {
                        maxSpeedStr.replace(" mph", "", ignoreCase = true).toIntOrNull()
                    }
                    maxSpeedStr.isNotEmpty() -> {
                        // Convert km/h to mph for storage
                        maxSpeedStr.toIntOrNull()?.div(1.60934)?.toInt()
                    }
                    else -> null
                }

                if (maxSpeed != null) {
                    Log.d("VroomHero", "Parsed speed limit: $maxSpeed km/h")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "API success: Speed limit $maxSpeed mph", Toast.LENGTH_SHORT).show()
                    }
                    val wayId = elements.getJSONObject(0).optString("id")
                    return@withContext Pair(maxSpeed, wayId)
                } else {
                    Log.w("VroomHero", "No valid maxspeed value in tags: $maxSpeedStr")
                    // Fallback for residential roads without maxspeed
                    if (tags.optString("highway") == "residential") {
                        Log.d("VroomHero", "Assuming default residential speed limit: 40 km/h (25 mph)")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Default residential speed limit: 40 km/h", Toast.LENGTH_SHORT).show()
                        }
                        val wayId = elements.getJSONObject(0).optString("id")
                        return@withContext Pair(40, wayId)                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "No maxspeed tag in API response", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.w("VroomHero", "No roads found in API response")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No roads found in API response", Toast.LENGTH_SHORT).show()
                }
            }
            Log.w("VroomHero", "No valid speed limit found in response")
            return@withContext Pair(null, null)
        } catch (e: Exception) {
            Log.e("VroomHero", "Error fetching speed limit: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "API error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return@withContext Pair(null, null)
        }
    }

    private fun updateGpsIndicator() {
        binding.gpsIndicator.setImageResource(
            if (isGpsActive) R.drawable.ic_gps_green else R.drawable.ic_gps_red
        )
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            isGpsActive = true
            updateGpsIndicator()
            Toast.makeText(this, "GPS enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            isGpsActive = false
            updateGpsIndicator()
            Toast.makeText(this, "GPS disabled", Toast.LENGTH_SHORT).show()
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