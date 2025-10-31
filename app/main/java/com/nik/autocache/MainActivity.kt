package com.nik.autocache

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {

    private val vm: AppListViewModel by viewModels()
    private lateinit var cleanDoneReceiver: CleanDoneReceiver

    // Compose-observable accessibility enabled flag
    private val accessibilityEnabledState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load installed apps
        vm.loadInstalledApps(applicationContext)

        // Register broadcast receiver (internal app broadcast) with RECEIVER_NOT_EXPORTED (Android 12+)
        cleanDoneReceiver = CleanDoneReceiver {
            // continue cleaning next entry when service broadcasts completion
            vm.continueCleaning(this@MainActivity)
        }
        registerReceiver(
            cleanDoneReceiver,
            android.content.IntentFilter(ACTION_CLEAN_DONE),
            Context.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme {
                Surface {
                    // pass the current accessibility enabled state and activity reference
                    AppListScreen(vm = vm, activity = this@MainActivity, accessibilityEnabled = accessibilityEnabledState.value)
                }
            }
        }

        // Show prompt to enable accessibility only if disabled
        if (!isAccessibilityServiceEnabled(this)) {
            promptEnableAccessibility()
        } else {
            // initial state true if already enabled
            accessibilityEnabledState.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check when user returns from settings
        val enabled = isAccessibilityServiceEnabled(this)
        accessibilityEnabledState.value = enabled
        if (enabled) {
            Toast.makeText(this, "Accessibility service enabled. AutoCache ready.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(cleanDoneReceiver) } catch (_: Exception) {}
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected = ComponentName(context, AppCleanerAccessibilityService::class.java).flattenToString()
        return enabledServices.contains(expected)
    }

    private fun promptEnableAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility")
            .setMessage(
                "AutoCache needs the Accessibility service to automate cleaning.\n\n" +
                        "Tap Open Settings, enable 'AutoCache' service, then return to this app."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }
}
