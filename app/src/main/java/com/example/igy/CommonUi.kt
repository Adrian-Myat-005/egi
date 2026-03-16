package com.example.igy

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector

// --- PROFESSIONAL CREAM PALETTE ---
val AppCream = Color(0xFFFDF5E6)
val AppWhite = Color(0xFFFFFFFF)
val AppShadow = Color(0xFFDCD4C4)
val AppDeepGray = Color(0xFF2F4F4F)
val AppAccent = Color(0xFF1E90FF)
val AppBlack = Color(0xFF121212)

@Composable
fun VroomEngineTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val colorScheme = if (isDarkMode) {
        darkColorScheme(
            primary = AppWhite,
            surface = AppBlack,
            background = AppBlack,
            onSurface = AppWhite,
            onBackground = AppWhite
        )
    } else {
        lightColorScheme(
            primary = AppDeepGray,
            surface = AppCream,
            background = AppCream,
            onSurface = AppDeepGray,
            onBackground = AppDeepGray
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
        ),
        content = content
    )
}

@Composable
fun VroomBackground(isDarkMode: Boolean, content: @Composable BoxScope.() -> Unit) {
    val bgColor = if (isDarkMode) AppBlack else AppCream
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        content()
    }
}

@Composable
fun TactileVroomButton(
    text: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    color: Color = AppDeepGray,
    isActive: Boolean = false,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val buttonBg = if (isDarkMode) Color(0xFF2A2A2A) else AppWhite
    val textColor = if (isDarkMode) AppWhite else AppDeepGray
    val shadowColor = if (isDarkMode) Color.Black else AppShadow

    // 3D Shadow Offset
    val shadowOffset = if (isPressed) 1.dp else 4.dp
    val scale = if (isPressed) 0.98f else 1f

    Box(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .padding(bottom = shadowOffset)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (!isLoading) onClick() }
            )
            .drawBehind {
                // Solid 3D Shadow
                if (!isPressed) {
                    drawRoundRect(
                        color = shadowColor,
                        topLeft = Offset(0f, 4.dp.toPx()),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(8.dp.toPx())
                    )
                }
            }
            .background(buttonBg, RoundedCornerShape(8.dp))
            .border(1.dp, if (isDarkMode) Color.White.copy(0.1f) else AppShadow, RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            Text(
                text = text,
                color = if (isActive) AppAccent else textColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun VroomCircularButton(
    isActive: Boolean,
    isBooting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val shadowOffset = if (isPressed) 2.dp else 8.dp
    val scale = if (isPressed) 0.96f else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(200.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .drawBehind {
                if (!isPressed) {
                    drawCircle(
                        color = AppShadow,
                        radius = (90.dp).toPx(),
                        center = center.copy(y = center.y + 8.dp.toPx())
                    )
                }
            }
            .clip(CircleShape)
            .background(AppWhite)
            .border(2.dp, AppShadow, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isBooting) {
                CircularProgressIndicator(color = AppDeepGray, modifier = Modifier.size(48.dp))
            } else {
                // Minimalist Toggle Icon
                Box(
                    modifier = Modifier
                        .size(60.dp, 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isActive) AppAccent else AppDeepGray.copy(0.2f))
                        .padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .align(if (isActive) Alignment.CenterEnd else Alignment.CenterStart)
                            .clip(CircleShape)
                            .background(AppWhite)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isActive) "ACTIVE" else "IGNITE",
                    color = AppDeepGray,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun VroomTactileSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppWhite)
            .border(1.dp, AppShadow, RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppDeepGray, fontWeight = FontWeight.Bold)
        
        Box(
            modifier = Modifier
                .size(48.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) AppAccent else AppDeepGray.copy(0.1f))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(AppWhite)
                    .drawBehind {
                        if (!checked) {
                            drawCircle(AppShadow.copy(0.5f), radius = size.width / 2, center = center.copy(y = center.y + 1.dp.toPx()))
                        }
                    }
            )
        }
    }
}

@Composable
fun BottomNavItem(label: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon, 
            contentDescription = label, 
            tint = if (isSelected) AppAccent else AppDeepGray.copy(0.4f), 
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label, 
            color = if (isSelected) AppAccent else AppDeepGray.copy(0.4f), 
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
