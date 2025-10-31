package com.nik.autocache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CleanDoneReceiver(private val onDone: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.autocache.ACTION_CLEAN_DONE") {
            onDone()
        }
    }
}
