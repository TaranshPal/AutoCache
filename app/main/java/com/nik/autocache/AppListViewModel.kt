package com.nik.autocache

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppInfo(val packageName: String, val label: String, val isSystem: Boolean = false)

class AppListViewModel : ViewModel() {

    fun startCleaning(activity: Activity, selectedPackages: List<String>) {
        // store the queue somewhere in the VM
        cleaningQueue.clear()
        cleaningQueue.addAll(selectedPackages)
        // start processing the first package
        processNext(activity)
    }

    private val _apps = MutableStateFlow(listOf<AppInfo>())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _selectedPackages = MutableStateFlow(setOf<String>())
    val selectedPackages: StateFlow<Set<String>> = _selectedPackages

    // queue of packages to be cleaned
    private val cleaningQueue = ArrayDeque<String>()

    /**
     * Load only user-installed (non-system) apps — NO QUERY_ALL_PACKAGES required.
     */
    fun loadInstalledApps(context: Context) = viewModelScope.launch {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString(), false) }
            .sortedBy { it.label.lowercase() }
        _apps.value = installed
    }

    fun toggleSelection(packageName: String) {
        val s = _selectedPackages.value.toMutableSet()
        if (s.contains(packageName)) s.remove(packageName) else s.add(packageName)
        _selectedPackages.value = s
    }

    fun selectAll() {
        val all = _apps.value.map { it.packageName }.toSet()
        _selectedPackages.value = all
    }

    fun clearSelection() {
        _selectedPackages.value = emptySet()
    }

    /**
     * Start the cleaning flow. Activity argument is required to open Settings pages.
     * If called from Compose, ensure you pass the host Activity reference.
     */
    fun startCleaning(activity: Activity? = null, vmActivity: Activity? = null) {
        viewModelScope.launch {
            if (_selectedPackages.value.isEmpty()) return@launch
            cleaningQueue.clear()
            cleaningQueue.addAll(_selectedPackages.value)

            // If Activity provided, proceed. Otherwise caller must later call continueCleaning(activity).
            if (activity != null) {
                processNext(activity)
            }
        }
    }

    private fun processNext(activity: Activity) {
        if (cleaningQueue.isEmpty()) {
            // Done: return to app
            val intent = Intent(activity, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(intent)
            return
        }

        val pkg = cleaningQueue.removeFirst()

        // send broadcast to tell service to prepare to clean this package
        val startIntent = Intent(ACTION_START_CLEAN).apply {
            putExtra(EXTRA_TARGET_PACKAGE, pkg)
        }
        activity.sendBroadcast(startIntent)


        // Open app info page for the package. Accessibility service will act.
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", pkg, null)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)

        // --- DELETE THESE TWO LINES ---
        // val stopIntent = Intent(ACTION_STOP_CLEAN)
        // activity.sendBroadcast(stopIntent)
        // ------------------------------
    }

    /**
     * Called by the Activity's BroadcastReceiver when the AccessibilityService broadcasts completion.
     * Activity must pass itself so processNext can open the next App Info screen.
     */
    fun continueCleaning(activity: Activity) {
        processNext(activity)
    }

}
