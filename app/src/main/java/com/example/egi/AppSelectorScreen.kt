package com.example.egi

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
    var currentMode by remember { mutableStateOf(EgiPreferences.getMode(context)) }
    var focusTarget by remember { mutableStateOf(EgiPreferences.getFocusTarget(context)) }
    var casualWhitelist by remember { mutableStateOf(EgiPreferences.getCasualWhitelist(context)) }
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
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text(
            text = "EGI >> NUCLEAR_TARGET_SELECTOR",
            color = Color.Yellow,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Green, fontFamily = FontFamily.Monospace),
            label = { Text("SEARCH_TARGET_APP", color = Color.Green.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Cyan,
                unfocusedBorderColor = Color.Green.copy(alpha = 0.5f),
                cursorColor = Color.Cyan
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SELECT PRIMARY APPLICATION FOR MAXIMUM PRIORITY. ALL OTHER TRAFFIC WILL BE TERMINATED.",
            color = Color.Red.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // VPN Tunnel Mode Toggle
        var isGlobal by remember { mutableStateOf(EgiPreferences.isVpnTunnelGlobal(context)) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(Color.DarkGray.copy(alpha = 0.3f))
                .clickable {
                    isGlobal = !isGlobal
                    EgiPreferences.setVpnTunnelMode(context, isGlobal)
                }
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("TUNNEL MODE", color = Color.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    if (isGlobal) "GLOBAL (ALL APPS)" else "SELECTED APPS ONLY", 
                    color = if (isGlobal) Color.Cyan else Color.Yellow, 
                    fontSize = 10.sp, 
                    fontFamily = FontFamily.Monospace
                )
            }
            Switch(
                checked = isGlobal,
                onCheckedChange = { 
                    isGlobal = it 
                    EgiPreferences.setVpnTunnelMode(context, it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Cyan,
                    checkedTrackColor = Color.DarkGray,
                    uncheckedThumbColor = Color.Yellow,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }

        // Mode Tabs
        Row(modifier = Modifier.fillMaxWidth()) {
            ModeTab(
                label = "FOCUS",
                isActive = currentMode == AppMode.FOCUS,
                modifier = Modifier.weight(1f),
                onClick = { 
                    currentMode = AppMode.FOCUS
                    EgiPreferences.saveMode(context, AppMode.FOCUS)
                }
            )
            ModeTab(
                label = "CASUAL",
                isActive = currentMode == AppMode.CASUAL,
                modifier = Modifier.weight(1f),
                onClick = { 
                    currentMode = AppMode.CASUAL
                    EgiPreferences.saveMode(context, AppMode.CASUAL)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredApps) { app ->
                SelectorRow(
                    app = app,
                    mode = currentMode,
                    isSelected = if (currentMode == AppMode.FOCUS) focusTarget == app.packageName else casualWhitelist.contains(app.packageName),
                    onSelect = {
                        if (currentMode == AppMode.FOCUS) {
                            focusTarget = app.packageName
                            EgiPreferences.saveFocusTarget(context, app.packageName)
                        } else {
                            val newList = casualWhitelist.toMutableSet()
                            if (newList.contains(app.packageName)) newList.remove(app.packageName) else newList.add(app.packageName)
                            casualWhitelist = newList
                            EgiPreferences.saveCasualWhitelist(context, newList)
                        }
                    }
                )
            }
        }

        Text(
            text = "[ BACK ]",
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(top = 16.dp)
                .align(Alignment.CenterHorizontally)
                .clickable { onBack() }
        )
    }
}

@Composable
fun ModeTab(label: String, isActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (isActive) Color.Green else Color.Transparent)
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color.Black else Color.Green,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SelectorRow(app: AppInfo, mode: AppMode, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberDrawablePainter(drawable = app.icon),
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.name, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(text = app.packageName, color = Color.Green.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }

        if (mode == AppMode.FOCUS) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = Color.Cyan, unselectedColor = Color.Green)
            )
        } else {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
                colors = CheckboxDefaults.colors(checkedColor = Color.Cyan, uncheckedColor = Color.Green, checkmarkColor = Color.Black)
            )
        }
    }
}
