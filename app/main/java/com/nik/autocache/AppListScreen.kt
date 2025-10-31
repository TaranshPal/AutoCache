package com.nik.autocache

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nik.autocache.AppItem

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun AppListScreen(vm: AppListViewModel, activity: Activity, accessibilityEnabled: Boolean) {
    val apps by vm.apps.collectAsState()
    val selected by vm.selectedPackages.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AutoCache — Installed Apps", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps) { app ->
                AppRow(app = app, selected = selected.contains(app.packageName)) {
                    vm.toggleSelection(app.packageName)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { vm.selectAll() }) { Text("Select All") }
            Button(onClick = { vm.clearSelection() }) { Text("Clear") }

            // Start button: disabled if accessibility not enabled
            Button(onClick = {
                if (!accessibilityEnabled) {
                    // Instead of starting, guide the user
                    Toast.makeText(activity, "Please enable Accessibility service first.", Toast.LENGTH_SHORT).show()
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@Button
                }
                // Safe: service is enabled, pass the Activity to ViewModel to open App Info pages
                vm.startCleaning(activity)
            }, enabled = accessibilityEnabled) {
                if (accessibilityEnabled) Text("Start Cleaning") else Text("Enable Accessibility")
            }
        }
    }
}
@Composable
fun AppRow(
    app: AppInfo,
    selected: Boolean,
    onClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(app.packageName) }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = app.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )

        Checkbox(
            checked = selected,
            onCheckedChange = { onClick(app.packageName) }
        )
    }
}