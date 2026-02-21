package com.example.igy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@Composable
fun AppPickerScreen(isDarkMode: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    var currentMode by remember { mutableStateOf(IgyPreferences.getMode(context)) }
    var focusTarget by remember { mutableStateOf(IgyPreferences.getFocusTarget(context) ?: "") }
    var casualWhitelist by remember { mutableStateOf(IgyPreferences.getCasualWhitelist(context)) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(creamColor)
            .padding(8.dp)
    ) {
        // --- MATRIX HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(cardBg)
                .border(0.5.dp, wheat)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "IGY >> FOCUS_MODE",
                    color = Color(0xFF8B008B),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, wheat)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("[ BACK ]", color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // Search Tile
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(cardBg)
                .border(0.5.dp, wheat)
                .padding(4.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("SEARCH_FOCUS_APP...", color = deepGray.copy(alpha = 0.3f), fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Color.Cyan
                )
            )
        }

        // Mode Tabs Grid
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            MatrixTab("FOCUS MODE", currentMode == AppMode.FOCUS, isDarkMode, Modifier.weight(1f), activeColor = Color(0xFF8B008B)) {
                currentMode = AppMode.FOCUS
                casualWhitelist = emptySet()
            }
            MatrixTab("CASUAL MODE", currentMode == AppMode.CASUAL, isDarkMode, Modifier.weight(1f), activeColor = Color(0xFF8B008B)) {
                currentMode = AppMode.CASUAL
                focusTarget = ""
            }
        }

        // List Grid
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(cardBg)
                .border(0.5.dp, wheat)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps) { app ->
                    MatrixAppRow(
                        app = app,
                        isDarkMode = isDarkMode,
                        isSelected = if (currentMode == AppMode.FOCUS) focusTarget == app.packageName else casualWhitelist.contains(app.packageName),
                        onToggle = {
                            if (currentMode == AppMode.FOCUS) {
                                focusTarget = if (focusTarget == app.packageName) "" else app.packageName
                            } else {
                                val newList = casualWhitelist.toMutableSet()
                                if (newList.contains(app.packageName)) newList.remove(app.packageName) else newList.add(app.packageName)
                                casualWhitelist = newList
                            }
                        }
                    )
                }
            }
        }

        // Confirm Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .background(cardBg)
                .border(1.dp, Color(0xFF8B008B))
                .clickable {
                    IgyPreferences.saveMode(context, currentMode)
                    IgyPreferences.saveFocusTarget(context, focusTarget)
                    IgyPreferences.saveCasualWhitelist(context, casualWhitelist)
                    Toast.makeText(context, "VIP TARGETS ARMED", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "[ CONFIRM SELECTION ]",
                color = Color(0xFF8B008B),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun MatrixAppRow(app: AppInfo, isDarkMode: Boolean, isSelected: Boolean, onToggle: () -> Unit) {
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(65.dp)
            .background(cardBg)
            .border(0.2.dp, wheat)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = rememberDrawablePainter(drawable = app.icon), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.name, color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
            Text(text = app.packageName, color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
        }
        Text(
            text = if (isSelected) "[ ACTIVE ]" else "[ STANDBY ]",
            color = if (isSelected) Color(0xFF8B008B) else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
