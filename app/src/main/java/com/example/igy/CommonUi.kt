package com.example.igy

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@Composable
fun MatrixTab(
    text: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF2E8B57),
    onClick: () -> Unit
) {
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isSelected) activeColor.copy(alpha = 0.1f) else cardBg)
            .border(0.5.dp, if (isSelected) activeColor else wheat)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) activeColor else deepGray.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
