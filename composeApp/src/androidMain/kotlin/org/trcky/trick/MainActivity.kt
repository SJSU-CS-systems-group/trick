package org.trcky.trick

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import org.trcky.trick.BuildConfig
import org.trcky.trick.metrics.StressTestReceiver
import org.trcky.trick.screens.messaging.WifiAwareServiceImpl
import org.trcky.trick.signal.SignalSessionManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class MainActivity : ComponentActivity(), KoinComponent {

    // Track permission state
    private val permissionsGranted = mutableStateOf(false)
    private val wifiAwareSupported = mutableStateOf(false)

    // Runtime-registered receiver so implicit ADB broadcasts are delivered (Android 8+)
    private val stressTestReceiver = StressTestReceiver()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions granted, update state
            permissionsGranted.value = true
        } else {
            // Handle permission denial
            permissionsGranted.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register StressTestReceiver at runtime — debug builds only.
        if (BuildConfig.DEBUG) {
            val stressFilter = IntentFilter().apply {
                addAction(StressTestReceiver.ACTION_STRESS_TEST)
                addAction(StressTestReceiver.ACTION_CANCEL)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stressTestReceiver, stressFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(stressTestReceiver, stressFilter)
            }
        }

        // Check WiFi Aware support FIRST
        wifiAwareSupported.value = isWifiAwareSupported()

        // Only request permissions if WiFi Aware is supported
        if (wifiAwareSupported.value) {
            if (checkPermissions()) {
                permissionsGranted.value = true
            } else {
                requestRequiredPermissions()
            }
        }

        setContent {
            // Remove when https://issuetracker.google.com/issues/364713509 is fixed
            LaunchedEffect(isSystemInDarkTheme()) {
                enableEdgeToEdge()
            }

            // Get SignalSessionManager from Koin and initialize it
            val signalSessionManager: SignalSessionManager = get()

            // Initialize Signal protocol on startup
            LaunchedEffect(Unit) {
                signalSessionManager.initialize()
                signalSessionManager.replenishPreKeysIfNeeded()
            }

            // Create WifiAwareService with SignalSessionManager
            val wifiAwareService = remember { WifiAwareServiceImpl(this@MainActivity, signalSessionManager) }

            AndroidApp(
                wifiAwareService = wifiAwareService,
                permissionsGranted = permissionsGranted.value,
                wifiAwareSupported = wifiAwareSupported.value
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) {
            try {
                unregisterReceiver(stressTestReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was already unregistered
            }
        }
    }

    /**
     * Check if WiFi Aware is supported on this device
     */
    private fun isWifiAwareSupported(): Boolean {
        // Check API level
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        
        // Check if WiFi Aware system service exists
        val wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            return false
        }
        
        // Check if WiFi Aware is available (requires permissions, but we can check PackageManager)
        return packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }
    
    private fun checkPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestRequiredPermissions() {
        val requiredPermissions = getRequiredPermissions()

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) return

        val needsLocationRationale = permissionsToRequest.contains(Manifest.permission.ACCESS_FINE_LOCATION)
            && shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)

        if (needsLocationRationale) {
            AlertDialog.Builder(this)
                .setTitle("Location Permission Required")
                .setMessage(
                    "Trick uses Wi-Fi Aware to discover and connect to nearby devices for " +
                    "peer-to-peer messaging. Android requires Location permission to use " +
                    "Wi-Fi Aware. Your location is never stored or shared."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    /**
     * Get required permissions based on API level
     */
    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            // Android 10-12 (API 29-32)
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
}
