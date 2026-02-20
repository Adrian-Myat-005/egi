package com.example.egi

import android.content.Intent
import android.content.pm.PackageManager
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
fun AppSelectorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val creamColor = Color(0xFFFDF5E6)
    val deepGray = Color(0xFF2F4F4F)
    val wheat = Color(0xFFF5DEB3)
    var currentMode by remember { mutableStateOf(EgiPreferences.getMode(context)) }
    var focusTarget by remember { mutableStateOf(EgiPreferences.getFocusTarget(context) ?: "") }
    var casualWhitelist by remember { mutableStateOf(EgiPreferences.getCasualWhitelist(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var allowedDomains by remember { mutableStateOf(EgiPreferences.getAllowedDomains(context)) }

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
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(Color.White)
                .border(0.5.dp, wheat),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = " EGI >> NUCLEAR_SELECTOR",
                color = Color(0xFFB8860B),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(80.dp)
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
                .background(Color.White)
                .border(0.5.dp, wheat)
                .padding(4.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = androidx.compose.ui.text.TextStyle(color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text("SEARCH_TARGET_APP...", color = deepGray.copy(alpha = 0.3f), fontSize = 12.sp) },
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
            MatrixTab("FOCUS MODE", currentMode == AppMode.FOCUS, Modifier.weight(1f)) {
                currentMode = AppMode.FOCUS
                casualWhitelist = emptySet()
            }
            MatrixTab("CASUAL MODE", currentMode == AppMode.CASUAL, Modifier.weight(1f)) {
                currentMode = AppMode.CASUAL
                focusTarget = ""
            }
        }

        // App List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .border(0.5.dp, wheat)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredApps) { app ->
                    MatrixSelectorRow(
                        app = app,
                        mode = currentMode,
                        isSelected = if (currentMode == AppMode.FOCUS) focusTarget == app.packageName else casualWhitelist.contains(app.packageName),
                        onSelect = {
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
                .background(Color.White)
                .border(1.dp, Color(0xFF2E8B57))
                .clickable {
                    EgiPreferences.saveMode(context, currentMode)
                    EgiPreferences.saveFocusTarget(context, focusTarget)
                    EgiPreferences.saveCasualWhitelist(context, casualWhitelist)
                    EgiPreferences.saveAllowedDomains(context, allowedDomains)
                    Toast.makeText(context, "TARGETS ARMED", Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "[ CONFIRM SELECTION ]",
                color = Color(0xFF2E8B57),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun MatrixSelectorRow(app: AppInfo, mode: AppMode, isSelected: Boolean, onSelect: () -> Unit) {
    val deepGray = Color(0xFF2F4F4F)
    val wheat = Color(0xFFF5DEB3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .border(0.2.dp, wheat)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = app.icon),
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.name, color = deepGray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1)
            Text(text = app.packageName, color = deepGray.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
        }
        Text(
            text = if (isSelected) "[X]" else "[ ]",
            color = if (isSelected) Color(0xFF4682B4) else deepGray.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp
        )
    }
}

