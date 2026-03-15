package com.example.igy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun AutoStartPickerScreen(isDarkMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedApps by remember { mutableStateOf(IgyPreferences.getAutoStartApps(context)) }
    var searchQuery by remember { mutableStateOf("") }
    
    val installedApps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { 
                AppInfo(
                    name = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm)
                )
            }
            .sortedBy { it.name }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isEmpty()) installedApps
        else installedApps.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    VroomBackground(isDarkMode) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Text("AUTO-START TARGETS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Select apps to trigger Vroom automatically", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            
            Spacer(modifier = Modifier.height(24.dp))

            // Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps...", color = Color.White.copy(alpha = 0.3f)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.5f)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00BFFF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App List
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredApps) { app ->
                    VroomAutoStartRow(
                        app = app,
                        isSelected = selectedApps.contains(app.packageName),
                        onToggle = {
                            val newList = selectedApps.toMutableSet()
                            if (newList.contains(app.packageName)) newList.remove(app.packageName) else newList.add(app.packageName)
                            selectedApps = newList
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TactileVroomButton("BACK", modifier = Modifier.weight(1f), onClick = onBack, isDarkMode = isDarkMode, color = Color.White.copy(alpha = 0.2f))
                TactileVroomButton("SAVE", modifier = Modifier.weight(1f), onClick = {
                    IgyPreferences.setAutoStartApps(context, selectedApps)
                    Toast.makeText(context, "Settings Saved", Toast.LENGTH_SHORT).show()
                    onBack()
                }, isDarkMode = isDarkMode, color = Color(0xFF00BFFF))
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun VroomAutoStartRow(app: AppInfo, isSelected: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFF00BFFF) else Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(drawable = app.icon), 
                contentDescription = null, 
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = app.packageName, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
            }
            Checkbox(
                checked = isSelected, 
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00BFFF))
            )
        }
    }
}
