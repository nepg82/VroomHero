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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.POST
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var apiService: ApiService
    private var lastSpeedLimit: Double? = null
    private var lastApiCallTime: Long = 0
    private var lastToastTime: Long = 0
    private var lastMovementTime: Long = 0
    private var speedCheckJob: Job? = null
    private var isSpeedTextRed = true // Tracks if text is red (true) or white (false)
    private var isSpeedLimitCardVisible = true // Tracks if card is visible and API is active
    private val speedThreshold = 0.5 // mph, below this is considered stopped
    private val timeoutDuration = 3000L // 3 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            binding.speedNumberTextView.setTextColor(ContextCompat.getColor(this, R.color.retro_red))
            showToast("VroomHero Started! YAHTZEE!")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to inflate layout", e)
            showToast("UI initialization failed")
            finish()
            return
        }
// just to make an edit so I can save changes
        try {
            apiService = RetrofitClient.apiService
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize Retrofit", e)
            showToast("Network setup failed")
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocationPermissions()
        startSpeedTimeoutCheck()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_color -> {
                isSpeedTextRed = !isSpeedTextRed
                binding.speedNumberTextView.setTextColor(
                    ContextCompat.getColor(this, if (isSpeedTextRed) R.color.retro_red else android.R.color.white)
                )
                true
            }
            R.id.action_toggle_card -> {
                isSpeedLimitCardVisible = !isSpeedLimitCardVisible
                binding.speedLimitCardView.visibility = if (isSpeedLimitCardVisible) View.VISIBLE else View.GONE
                // Adjust speedNumberTextView constraints
                val layoutParams = binding.speedNumberTextView.layoutParams as ConstraintLayout.LayoutParams
                if (isSpeedLimitCardVisible) {
                    layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.endToStart = R.id.guideline_66
                    layoutParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                } else {
                    layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    layoutParams.endToStart = ConstraintLayout.LayoutParams.UNSET
                    layoutParams.horizontalBias = 0.5f // Center horizontally
                }
                binding.speedNumberTextView.layoutParams = layoutParams
                binding.speedNumberTextView.requestLayout() // Force layout update
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startSpeedTimeoutCheck() {
        speedCheckJob?.cancel() // Cancel any existing job
        speedCheckJob = lifecycleScope.launch {
            while (true) {
                delay(1000L) // Check every second
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMovementTime > timeoutDuration) {
                    withContext(Dispatchers.Main) {
                        binding.speedNumberTextView.text = "00"
 //                       binding.speedUnitsTextView.text = "mph"
                        Log.d("VroomHero", "Speed reset to 00 due to timeout")
                    }
                }
            }
        }
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
//            binding.speedUnitsTextView.text = ""
            binding.speedLimitTextView.text = "XX"
            binding.roadNameTextView?.text = ""
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
            } else {
                Log.w("MainActivity", "Location permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Security exception in location updates", e)
            showToast("Location permission error")
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            val speedMph = location.speed * 2.23694 // m/s to MPH
            val currentTime = System.currentTimeMillis()
            if (speedMph > speedThreshold) {
                lastMovementTime = currentTime
                val formattedSpeed = String.format("%02d", speedMph.toInt() % 100) // Integer, padded to 2 digits
                binding.speedNumberTextView.text = formattedSpeed
//                binding.speedUnitsTextView.text = "mph"
            } else {
                // If speed is low, rely on timeout to set "00"
                Log.d("VroomHero", "Speed below threshold: $speedMph mph")
            }

            val lat = location.latitude
            val lon = location.longitude

            // Throttle API calls to every 10 seconds
            if (currentTime - lastApiCallTime < 10_000) {
                Log.d("VroomHero", "API call throttled, last call: ${(currentTime - lastApiCallTime)/1000}s ago")
                if (lastSpeedLimit != null) {
                    binding.speedLimitTextView.text = String.format("%.0f", lastSpeedLimit!!.toFloat())
//                    showToast("Used last speed limit: $lastSpeedLimit mph")
                } else {
                    binding.speedLimitTextView.text = "XX"
                    binding.roadNameTextView?.text = ""
                }
                return
            }

            // Fetch speed limit and road name from API if card is visible
            if (isSpeedLimitCardVisible) {
                lifecycleScope.launch {
                    val (speedLimit, wayId, roadName) = fetchSpeedLimitFromOsm(lat, lon)
                    Log.d("VroomHero", "Fetched speedLimit: $speedLimit, wayId: $wayId, roadName: $roadName")
                    if (speedLimit != null && wayId != null) {
                        binding.speedLimitTextView.text = String.format("%.0f", speedLimit.toFloat())
                        binding.roadNameTextView?.text = roadName ?: "Unknown Road"
                        lastSpeedLimit = speedLimit
                    } else {
                        binding.speedLimitTextView.text = "XX"
                        binding.roadNameTextView?.text = ""
                        lastSpeedLimit = null
                    }
                    lastApiCallTime = currentTime
                }
            } else {
                Log.d("VroomHero", "API call skipped: Speed limit card is hidden")
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing location update", e)
            showToast("Error updating speed: ${e.message}")
            binding.roadNameTextView?.text = ""
        }
    }

    suspend fun fetchSpeedLimitFromOsm(lat: Double, lon: Double): Triple<Double?, String?, String?> = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
            }
            val query = """
                [out:json][timeout:30];
                way(around:10,$lat,$lon)["highway"~"^(residential|primary|secondary|tertiary|motorway)$"]["maxspeed"];
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
//                    showToast("No roads found in API response")
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
//                        showToast("API success: Speed limit $maxSpeed mph")
                    }
                    val wayId = element.optString("id")
                    return@withContext Triple(maxSpeed, wayId, roadName.takeIf { it.isNotEmpty() })
                } else {
                    Log.w("VroomHero", "No valid maxspeed value in tags: $maxSpeedStr")
                    withContext(Dispatchers.Main) {
//                        showToast("No maxspeed tag in API response")
                    }
                }
            } else {
                Log.w("VroomHero", "No roads found in API response")
                withContext(Dispatchers.Main) {
//                    showToast("No roads found in API response")
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

    private fun showToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 2000) { // 2-second debounce
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            lastToastTime = now
        }
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(this)
            speedCheckJob?.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error removing location updates or cancelling job", e)
        }
    }
}

interface ApiService {
    @POST("interpreter")
    suspend fun getOsmData(@Body query: String): String
}