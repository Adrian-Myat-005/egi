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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
fun VroomAutoStartPickerScreen(isDarkMode: Boolean, onBack: () -> Unit) {
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
            Text("AUTO-START TARGETS", color = if (isDarkMode) Color.White else AppDeepGray, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Select apps to trigger Vroom automatically", color = (if (isDarkMode) Color.White else AppDeepGray).copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            
            Spacer(modifier = Modifier.height(24.dp))

            // Search Field - Tactile Style
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().background(if (isDarkMode) Color(0xFF1A1A1A) else AppWhite, RoundedCornerShape(8.dp)),
                placeholder = { Text("Search apps...", color = AppDeepGray.copy(alpha = 0.3f), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppDeepGray.copy(alpha = 0.5f)) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = if (isDarkMode) Color.White else AppDeepGray,
                    unfocusedTextColor = if (isDarkMode) Color.White else AppDeepGray,
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppShadow
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App List
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredApps) { app ->
                    VroomAutoStartRow(
                        app = app,
                        isSelected = selectedApps.contains(app.packageName),
                        isDarkMode = isDarkMode,
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
                TactileVroomButton("BACK", modifier = Modifier.weight(1f), isDarkMode = isDarkMode, onClick = onBack)
                TactileVroomButton("SAVE", modifier = Modifier.weight(1f), isDarkMode = isDarkMode, onClick = {
                    IgyPreferences.setAutoStartApps(context, selectedApps)
                    Toast.makeText(context, "Config Saved", Toast.LENGTH_SHORT).show()
                    onBack()
                })
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun VroomAutoStartRow(app: AppInfo, isSelected: Boolean, isDarkMode: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isDarkMode) (if (isSelected) Color(0xFF2A2A2A) else Color(0xFF1A1A1A)) else AppWhite,
        border = BorderStroke(1.dp, if (isSelected) AppAccent else AppShadow)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(drawable = app.icon), 
                contentDescription = null, 
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, AppShadow, RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.name, color = if (isDarkMode) Color.White else AppDeepGray, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, fontFamily = FontFamily.SansSerif)
                Text(text = app.packageName, color = (if (isDarkMode) Color.White else AppDeepGray).copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
            }
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) AppAccent else AppDeepGray.copy(0.2f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
