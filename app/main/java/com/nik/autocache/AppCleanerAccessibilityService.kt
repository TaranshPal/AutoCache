package com.nik.autocache

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

const val ACTION_CLEAN_DONE = "com.autocache.ACTION_CLEAN_DONE"
const val ACTION_START_CLEAN = "com.autocache.ACTION_START_CLEAN"
const val ACTION_STOP_CLEAN = "com.autocache.ACTION_STOP_CLEAN"
const val EXTRA_TARGET_PACKAGE = "com.autocache.EXTRA_TARGET_PACKAGE"

/**
 * Accessibility service that only acts when explicitly told to (via ACTION_START_CLEAN).
 * It will only attempt to clear cache for the current target package and only within a small time window.
 */
class AppCleanerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Controlled via broadcast from the app before launching App Info
    @Volatile private var isCleaning = false
    @Volatile private var isProcessing = false // NEW: Lock to prevent event spam
    @Volatile private var targetPackage: String? = null
    @Volatile private var startTimeMillis: Long = 0L
    private val CLEAN_WINDOW_MS = 15_000L // 15 seconds safe window by default

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_START_CLEAN) {
                targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                isCleaning = true
                isProcessing = false // Reset lock
                startTimeMillis = System.currentTimeMillis()
            } else if (intent?.action == ACTION_STOP_CLEAN) {
                isCleaning = false
                isProcessing = false
                targetPackage = null
                startTimeMillis = 0L
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        // register receiver for start/stop cleaning
        val filter = IntentFilter().apply {
            addAction(ACTION_START_CLEAN)
            addAction(ACTION_STOP_CLEAN)
        }
        registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only act if we are in the active cleaning window AND not already processing
        if (!isCleaning || isProcessing) return
        if (System.currentTimeMillis() - startTimeMillis > CLEAN_WINDOW_MS) {
            // timed out; be safe and stop acting automatically
            isCleaning = false
            isProcessing = false
            targetPackage = null
            return
        }

        // We're only interested if the Settings app is visible
        val pkg = event.packageName?.toString() ?: ""
        if (!pkg.contains("settings", ignoreCase = true)) return

        val root = rootInActiveWindow ?: return

        // Set the lock
        isProcessing = true

        // Add a delay. The event might fire before the page content is fully drawn.
        // Increased to 800ms for emulators
        handler.postDelayed({
            val currentRoot = rootInActiveWindow
            if (currentRoot == null) {
                isProcessing = false // Release lock if root is gone
                return@postDelayed
            }
            tryClearCacheFromAppInfo(currentRoot)
            // isProcessing will be released by notifyAppCleaningDone()
        }, 800)
    }

    private fun tryClearCacheFromAppInfo(root: AccessibilityNodeInfo) {
        // 1) Look for clear cache button text directly
        val clearTexts = listOf(
            "Clear cache", "Clear cache data", "Clear Cache", "Clear app cache"
        )
        if (clickByTexts(root, clearTexts)) {
            handler.postDelayed({ confirmIfDialogAppearsSafe(root) }, 600)
            handler.postDelayed({ notifyAppCleaningDone() }, 1500) // Increased delay
            return
        }

        // 2) Look for Storage / Storage & cache then attempt Clear cache
        val storageTexts = listOf("Storage", "Storage & cache", "Storage usage", "Storage & data")
        if (clickByTexts(root, storageTexts)) {
            // Increased delay to 1500ms for emulator screen transition
            handler.postDelayed({
                val nextRoot = rootInActiveWindow
                if (nextRoot != null) {
                    if (clickByTexts(nextRoot, clearTexts)) {
                        handler.postDelayed({ confirmIfDialogAppearsSafe(nextRoot) }, 600)
                        handler.postDelayed({ notifyAppCleaningDone() }, 1500) // Increased delay
                    } else {
                        // couldn't find cache button on storage screen; bail safely
                        Toast.makeText(this, "AutoCache couldn't find cache button.", Toast.LENGTH_SHORT).show()
                        notifyAppCleaningDone() // <<< FAILURE PATH
                    }
                } else {
                    Toast.makeText(this, "AutoCache: Screen transition failed.", Toast.LENGTH_SHORT).show()
                    notifyAppCleaningDone() // <<< FAILURE PATH
                }
            }, 1500) // <<< INCREASED DELAY
            return
        }

        // If nothing is found, do NOT click generic buttons. Inform user.
        Toast.makeText(this, "AutoCache couldn't find cache button. Please clear manually.", Toast.LENGTH_LONG).show()
        notifyAppCleaningDone() // <<< FAILURE PATH
    }

    /**
     * Searches for text strings and clicks their clickable parent if found.
     */
    private fun clickByTexts(root: AccessibilityNodeInfo, texts: List<String>): Boolean {
        for (t in texts) {
            val found = root.findAccessibilityNodeInfosByText(t)
            if (!found.isNullOrEmpty()) {
                for (node in found) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else {
                        var parent = node.parent
                        while (parent != null) {
                            if (parent.isClickable) {
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                return true
                            }
                            parent = parent.parent
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * Safer confirmation: before clicking a dialog positive button,
     * check the dialog's text for "cache" and ensure it does not mention "data" or "storage".
     */
    private fun confirmIfDialogAppearsSafe(root: AccessibilityNodeInfo) {
        val positiveButtons = mutableListOf<AccessibilityNodeInfo>()
        val byId = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (!byId.isNullOrEmpty()) positiveButtons.addAll(byId)

        val confirmTexts = listOf("OK", "Ok", "ok", "Confirm", "Yes", "Clear") // Added "Clear"
        for (t in confirmTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            if (!nodes.isNullOrEmpty()) positiveButtons.addAll(nodes)
        }

        for (btn in positiveButtons.distinct()) { // Use distinct() to avoid clicking twice
            // find top-level dialog root
            var dialogRoot: AccessibilityNodeInfo? = btn
            while (dialogRoot?.parent != null) dialogRoot = dialogRoot.parent
            if (dialogRoot == null) continue

            val mentionsCache = !dialogRoot.findAccessibilityNodeInfosByText("cache").isNullOrEmpty()
            val mentionsData = !dialogRoot.findAccessibilityNodeInfosByText("data").isNullOrEmpty()
            val mentionsStorage = !dialogRoot.findAccessibilityNodeInfosByText("storage").isNullOrEmpty()

            // Click only when dialog explicitly mentions "cache" and NOT "data" or "storage"
            // OR if it's a simple dialog that doesn't mention any of them (could be simple "OK")
            val isSafeClick = (mentionsCache && !mentionsData && !mentionsStorage)
            val isSimpleConfirm = !mentionsCache && !mentionsData && !mentionsStorage

            if ((isSafeClick || isSimpleConfirm) && btn.isClickable) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    private fun notifyAppCleaningDone() {
        val intent = Intent(ACTION_CLEAN_DONE)
        sendBroadcast(intent)
        // Also stop auto-action until next explicit start
        isCleaning = false
        targetPackage = null
        startTimeMillis = 0L
        isProcessing = false // <<< RELEASE THE LOCK HERE
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) { /* ignore */ }
    }
}