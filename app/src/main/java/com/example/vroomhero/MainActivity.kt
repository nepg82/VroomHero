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
                    val cachedSpeedLimit = database.speedLimitDao().getSpeedLimit(newRoadId)
                    if (cachedSpeedLimit != null) {
                        val speedLimitMph = cachedSpeedLimit.speedLimit * 0.621371 // km/h to MPH
                        binding.speedLimitTextView.text = String.format("%.0f", speedLimitMph)
                    } else {
                        val speedLimit = fetchSpeedLimitFromOsm(lat, lon)
                        if (speedLimit != null) {
                            database.speedLimitDao().insert(
                                SpeedLimitEntity(roadId = newRoadId, speedLimit = speedLimit)
                            )
                            val speedLimitMph = speedLimit * 0.621371 // km/h to MPH
                            binding.speedLimitTextView.text = String.format("%.0f", speedLimitMph)
                        } else {
                            binding.speedLimitTextView.text = "XX"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing location update", e)
            Toast.makeText(this, "Error updating speed", Toast.LENGTH_SHORT).show()
            isGpsActive = false
            updateGpsIndicator()
        }
    }

    suspend fun fetchSpeedLimitFromOsm(lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:30];
                way(around:25,$lat,$lon)["highway"]["maxspeed"];
                out tags;
            """.trimIndent()

            val response = apiService.getOsmData(query)
            if (response.isEmpty()) {
                Log.e("VroomHero", "Empty response from Overpass API")
                return@withContext null
            }

            val json = JSONObject(response)
            val elements = json.optJSONArray("elements") ?: return@withContext null
            if (elements.length() > 0) {
                val tags = elements.getJSONObject(0).optJSONObject("tags") ?: return@withContext null
                val maxSpeed = tags.optString("maxspeed").toIntOrNull()
                if (maxSpeed != null) {
                    Log.d("VroomHero", "Fetched speed limit: $maxSpeed")
                    return@withContext maxSpeed
                }
            }
            Log.w("VroomHero", "No valid speed limit found in response")
            return@withContext null
        } catch (e: Exception) {
            Log.e("VroomHero", "Error fetching speed limit: ${e.message}")
            return@withContext null
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
    @POST("api/interpreter")
    suspend fun getOsmData(@Body query: String): String
}