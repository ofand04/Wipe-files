package com.example.wipefiles // Update to match your actual package name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val STORAGE_PERMISSION_CODE = 100
        private const val NOTIFICATION_PERMISSION_CODE = 101
        private const val SETTINGS_REQUEST_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Keep your existing layout

        // Request permissions when app launches
        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Check if user granted MANAGE_EXTERNAL_STORAGE after returning from settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                onStoragePermissionGranted()
            }
        }
    }

    private fun requestAllPermissions() {
        when {
            // Android 11+ (API 30+)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    showStoragePermissionRationale()
                } else {
                    onStoragePermissionGranted()
                }
            }
            // Android 6-10 (API 23-29)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val permissions = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                val notGranted = permissions.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (notGranted.isEmpty()) {
                    onStoragePermissionGranted()
                } else {
                    ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), STORAGE_PERMISSION_CODE)
                }
            }
            // Android 5.1 and below - no runtime permissions needed
            else -> {
                onStoragePermissionGranted()
            }
        }

        // Also request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun showStoragePermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("This app needs full access to your files to clean and manage storage. " +
                        "Please grant 'All files access' in the next screen.")
            .setPositiveButton("Grant Permission") { _, _ ->
                openAllFilesAccessSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Storage permission is required for the app to function", 
                    Toast.LENGTH_LONG).show()
                // Optionally finish() here if you want to close the app
            }
            .setCancelable(false)
            .show()
    }

    private fun openAllFilesAccessSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        
        try {
            startActivityForResult(intent, SETTINGS_REQUEST_CODE)
        } catch (e: Exception) {
            // Fallback for devices without the per-app screen
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(fallbackIntent)
        }
    }

    private fun onStoragePermissionGranted() {
        // Your app can now access all files
        Toast.makeText(this, "Storage access granted!", Toast.LENGTH_SHORT).show()
        
        // Initialize your wipe/clean functionality here
        // initializeWipeFunctionality()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            STORAGE_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    onStoragePermissionGranted()
                } else {
                    // Check if user permanently denied - show settings dialog
                    if (shouldShowRequestPermissionRationale(*permissions)) {
                        Toast.makeText(this, "Permission denied. Please grant manually in settings.", 
                            Toast.LENGTH_LONG).show()
                    } else {
                        // User checked "Don't ask again" - redirect to app settings
                        AlertDialog.Builder(this)
                            .setTitle("Permission Denied")
                            .setMessage("You permanently denied storage permission. " +
                                        "Please enable it manually in app settings.")
                            .setPositiveButton("Open Settings") { _, _ ->
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }
                            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
            }
            NOTIFICATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Notification permission granted
                } else {
                    Toast.makeText(this, "Notifications disabled. Enable in settings if needed.", 
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE) {
            if (Environment.isExternalStorageManager()) {
                onStoragePermissionGranted()
            } else {
                Toast.makeText(this, "Storage permission was not granted", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // Helper: Create files safely with proper permissions
    fun createFileInDownloads(fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+ use MediaStore or SAF
                // With MANAGE_EXTERNAL_STORAGE, direct paths work
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}