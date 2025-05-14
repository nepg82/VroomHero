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
import androidx.room.Room
import retrofit2.http.GET
import retrofit2.http.Query
import org.json.JSONArray

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
    private lateinit var db: AppDatabase
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

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "vroomhero-database"
        ).build()
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
                    val (speedLimit, _, roadName) = fetchRoadDataFromHere(lat, lon)
                    Log.d("VroomHero", "Fetched speedLimit: $speedLimit, roadName: $roadName")
                    if (speedLimit != null) {
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

    suspend fun fetchRoadDataFromHere(lat: Double, lon: Double): Triple<Double?, String?, String?> = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cachedData = db.roadDataDao().getRoadDataByLocation(lat, lon)
            if (cachedData != null && System.currentTimeMillis() - cachedData.timestamp < 24 * 60 * 60 * 1000) { // 24-hour cache validity
                Log.d("VroomHero", "Using cached data: speedLimit=${cachedData.speedLimit}, roadName=${cachedData.roadName}")
                return@withContext Triple(cachedData.speedLimit, null, cachedData.roadName)
            }

            // Fetch from HERE API
            val coordinates = "$lat,$lon"
            val response = apiService.getRoadData(coordinates, RetrofitClient.API_KEY)
            Log.d("VroomHero", "Raw HERE API response: $response")

            val json = JSONObject(response)
            val items = json.getJSONArray("items")
            if (items.length() > 0) {
                val item = items.getJSONObject(0)
                val address = item.getJSONObject("address")
                val roadName = address.optString("street", "Unknown Road")
                val speedLimit = item.optJSONObject("speedLimit")?.optDouble("value")?.div(1.60934) // km/h to mph

                // Cache the result
                val roadData = RoadData(
                    latitude = lat,
                    longitude = lon,
                    speedLimit = speedLimit,
                    roadName = roadName,
                    timestamp = System.currentTimeMillis()
                )
                db.roadDataDao().insert(roadData)
                Log.d("VroomHero", "Fetched and cached: speedLimit=$speedLimit, roadName=$roadName")

                return@withContext Triple(speedLimit, null, roadName)
            }
            Log.w("VroomHero", "No road data found in HERE API response")
            return@withContext Triple(null, null, null)
        } catch (e: Exception) {
            Log.e("VroomHero", "Error fetching road data: ${e.message}", e)
            withContext(Dispatchers.Main) {
                showToast("API error: ${e.message}")
            }
            // Fallback to cache if available
            val cachedData = db.roadDataDao().getRoadDataByLocation(lat, lon)
            if (cachedData != null) {
                Log.d("VroomHero", "Using cached data on error: speedLimit=${cachedData.speedLimit}, roadName=${cachedData.roadName}")
                return@withContext Triple(cachedData.speedLimit, null, cachedData.roadName)
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
    @GET("revgeocode")
    suspend fun getRoadData(
        @Query("at") coordinates: String,
        @Query("apiKey") apiKey: String = RetrofitClient.API_KEY
    ): String
}