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
import android.content.SharedPreferences

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private var apiService: ApiService? = null
    private var lastSpeedLimit: Double? = null
    private var lastApiCallTime: Long = 0
    private var lastToastTime: Long = 0
    private var lastMovementTime: Long = 0
    private var speedCheckJob: Job? = null
    private var isSpeedTextRed = true
    private var isSpeedLimitCardVisible = true
    private val speedThreshold = 0.5
    private val timeoutDuration = 3000L
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            sharedPreferences = getSharedPreferences("VroomHeroPrefs", Context.MODE_PRIVATE)
            isSpeedTextRed = sharedPreferences.getBoolean("isSpeedTextRed", true)
            binding.speedNumberTextView.setTextColor(
                ContextCompat.getColor(this, if (isSpeedTextRed) R.color.retro_red else android.R.color.white)
            )
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
            apiService = null
            updateCardVisibility()
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocationPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Resume location updates and speed timeout check if permissions are granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            startSpeedTimeoutCheck()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause location updates and speed timeout check
        try {
            locationManager.removeUpdates(this)
            speedCheckJob?.cancel()
            Log.d("VroomHero", "Location updates and speed timeout check paused")
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Error pausing location updates", e)
        }
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
                sharedPreferences.edit().putBoolean("isSpeedTextRed", isSpeedTextRed).apply()
                true
            }
            R.id.action_toggle_card -> {
                isSpeedLimitCardVisible = !isSpeedLimitCardVisible
                updateCardVisibility()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateCardVisibility() {
        val shouldShowCard = isSpeedLimitCardVisible && lastSpeedLimit != null && apiService != null
        binding.speedLimitCardView.visibility = if (shouldShowCard) View.VISIBLE else View.GONE
        val layoutParams = binding.speedNumberTextView.layoutParams as ConstraintLayout.LayoutParams
        if (shouldShowCard) {
            layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.endToStart = R.id.guideline_66
            layoutParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
        } else {
            layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.endToStart = ConstraintLayout.LayoutParams.UNSET
            layoutParams.horizontalBias = 0.5f
        }
        binding.speedNumberTextView.layoutParams = layoutParams
        binding.speedNumberTextView.requestLayout()

        // Update GPS indicator
        binding.gpsIndicator?.let { indicator ->
            indicator.visibility = when {
                shouldShowCard -> View.GONE
                !isSpeedLimitCardVisible || apiService == null -> {
                    indicator.setImageResource(R.drawable.ic_gps_red)
                    View.VISIBLE
                }
                else -> {
                    indicator.setImageResource(R.drawable.ic_gps_green)
                    View.VISIBLE
                }
            }
        } ?: run {
            Log.e("MainActivity", "gpsIndicator is null in binding")
        }
    }

    private fun startSpeedTimeoutCheck() {
        speedCheckJob?.cancel()
        speedCheckJob = lifecycleScope.launch {
            while (true) {
                delay(1000L)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastMovementTime > timeoutDuration) {
                    withContext(Dispatchers.Main) {
                        binding.speedNumberTextView.text = "00"
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
            startSpeedTimeoutCheck()
        } else {
            showToast("Location permissions denied. Speedometer disabled.")
            binding.speedNumberTextView.text = "N/A"
            binding.speedLimitTextView.text = "XX"
            binding.roadNameTextView?.text = ""
            binding.gpsIndicator?.setImageResource(R.drawable.ic_gps_red)
            binding.gpsIndicator?.visibility = View.VISIBLE
            updateCardVisibility()
        }
    }

    private fun requestLocationPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fineLocation == PackageManager.PERMISSION_GRANTED && coarseLocation == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            startSpeedTimeoutCheck()
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
                Log.d("VroomHero", "Location updates started")
            } else {
                Log.w("MainActivity", "Location permission not granted")
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Security exception in location updates", e)
            showToast("Location permission error")
            binding.gpsIndicator?.setImageResource(R.drawable.ic_gps_red)
            binding.gpsIndicator?.visibility = View.VISIBLE
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            val speedMph = location.speed * 2.23694
            val currentTime = System.currentTimeMillis()
            if (speedMph > speedThreshold) {
                lastMovementTime = currentTime
                val formattedSpeed = String.format("%02d", speedMph.toInt() % 100)
                binding.speedNumberTextView.text = formattedSpeed
            } else {
                Log.d("VroomHero", "Speed below threshold: $speedMph mph")
            }

            val lat = location.latitude
            val lon = location.longitude

            if (currentTime - lastApiCallTime < 10_000) {
                Log.d("VroomHero", "API call throttled, last call: ${(currentTime - lastApiCallTime)/1000}s ago")
                if (lastSpeedLimit != null) {
                    binding.speedLimitTextView.text = String.format("%.0f", lastSpeedLimit!!.toFloat())
                } else {
                    binding.speedLimitTextView.text = "XX"
                    binding.roadNameTextView?.text = ""
                }
                updateCardVisibility()
                return
            }

            if (isSpeedLimitCardVisible && apiService != null) {
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
                    updateCardVisibility()
                }
            } else {
                Log.d("VroomHero", "API call skipped: ${if (apiService == null) "API not initialized" else "Speed limit card toggled off"}")
                updateCardVisibility()
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing location update", e)
            showToast("Error updating speed: ${e.message}")
            binding.roadNameTextView?.text = ""
            lastSpeedLimit = null
            binding.gpsIndicator?.setImageResource(R.drawable.ic_gps_red)
            binding.gpsIndicator?.visibility = View.VISIBLE
            updateCardVisibility()
        }
    }

    suspend fun fetchSpeedLimitFromOsm(lat: Double, lon: Double): Triple<Double?, String?, String?> = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:30];
                way(around:10,$lat,$lon)["highway"~"^(residential|primary|secondary|tertiary|motorway)$"]["maxspeed"];
                out tags;
            """.trimIndent()

            Log.d("VroomHero", "Sending Overpass query: $query")
            val response = apiService!!.getOsmData(query)
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
                        maxSpeedStr.replace("[^0-9]".toRegex(), "").toDoubleOrNull()?.div(1.60934)
                    }
                    else -> null
                }

                if (maxSpeed != null) {
                    Log.d("VroomHero", "Parsed speed limit: $maxSpeed mph")
                    val wayId = element.optString("id")
                    return@withContext Triple(maxSpeed, wayId, roadName.takeIf { it.isNotEmpty() })
                } else {
                    Log.w("VroomHero", "No valid maxspeed value in tags: $maxSpeedStr")
                    withContext(Dispatchers.Main) {
                    }
                }
            } else {
                Log.w("VroomHero", "No roads found in API response")
                withContext(Dispatchers.Main) {
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
        if (now - lastToastTime > 2000) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            lastToastTime = now
        }
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

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