package net.discdd.trick

import android.Manifest
import android.content.Context
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
import androidx.core.content.ContextCompat
import net.discdd.trick.screens.messaging.WifiAwareServiceImpl

class MainActivity : ComponentActivity() {
    
    // Track permission state
    private val permissionsGranted = mutableStateOf(false)
    private val wifiAwareSupported = mutableStateOf(false)
    
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
            val wifiAwareService = WifiAwareServiceImpl(this@MainActivity)
            AndroidApp(
                wifiAwareService = wifiAwareService,
                permissionsGranted = permissionsGranted.value,
                wifiAwareSupported = wifiAwareSupported.value
            )
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
        
        if (permissionsToRequest.isNotEmpty()) {
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
