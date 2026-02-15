package com.example.egi

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

    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledPackages(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { 
                AppInfo(
                    name = it.applicationInfo.loadLabel(pm).toString(),
                    packageName = it.packageName,
                    icon = it.applicationInfo.loadIcon(pm)
                )
            }
            .sortedBy { it.name }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text(
            text = "EGI >> MODE_SELECTOR",
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

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
            items(installedApps) { app ->
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
