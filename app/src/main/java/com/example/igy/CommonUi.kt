package com.example.igy

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector

// Color definitions for consistency
val AppCream = Color(0xFFFDF5E6)
val AppWhite = Color(0xFFFFFFFF)
val AppShadow = Color(0xFFDCD4C4)
val AppDeepGray = Color(0xFF2F4F4F)
val AppAccent = Color(0xFF1E90FF)
val AppBlack = Color(0xFF121212)

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

@Composable
fun IgyEngineTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val colorScheme = if (isDarkMode) {
        androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF00BFFF), 
            onPrimary = Color.White, 
            surface = Color(0xFF020C1F), 
            onSurface = Color.White, 
            background = Color(0xFF020C1F), 
            onBackground = Color.White, 
            secondary = Color(0xFF00CED1), 
            outline = Color(0xFF1E90FF)
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF1E90FF), 
            onPrimary = Color.White, 
            surface = Color(0xFFF0F8FF), 
            onSurface = Color(0xFF020C1F), 
            background = Color(0xFFF0F8FF), 
            onBackground = Color(0xFF020C1F), 
            secondary = Color(0xFF4682B4), 
            outline = Color(0xFFADD8E6)
        )
    }

    androidx.compose.material3.MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.SansSerif, 
                fontWeight = FontWeight.Normal, 
                fontSize = 14.sp, 
                color = if (isDarkMode) Color.White else Color(0xFF020C1F)
            )
        ),
        content = content
    )
}

@Composable
fun IgyBackground(isDarkMode: Boolean, content: @Composable BoxScope.() -> Unit) {
    val bgColor = if (isDarkMode) Color(0xFF020C1F) else Color(0xFFE6F3FF)
    val secondaryColor = if (isDarkMode) Color(0xFF051937) else Color(0xFFCCE5FF)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(bgColor, secondaryColor)
                )
            )
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val dotRadius = 2.dp.toPx()
                val color = (if (isDarkMode) Color(0xFF1E90FF) else Color(0xFF4682B4)).copy(alpha = 0.1f)
                
                val points = listOf(
                    Offset(0.1f * size.width, 0.2f * size.height),
                    Offset(0.8f * size.width, 0.15f * size.height),
                    Offset(0.9f * size.width, 0.4f * size.height),
                    Offset(0.2f * size.width, 0.6f * size.height),
                    Offset(0.7f * size.width, 0.8f * size.height),
                    Offset(0.05f * size.width, 0.9f * size.height)
                )
                
                points.forEachIndexed { i, p1 ->
                    drawCircle(color, dotRadius, p1)
                    points.forEachIndexed { j, p2 ->
                        if (i < j) {
                            drawLine(color, p1, p2, strokeWidth)
                        }
                    }
                }
            }
    ) {
        content()
    }
}

@Composable
fun IgyCircularButton(
    isActive: Boolean,
    isBooting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "Scale")
    
    val infiniteTransition = rememberInfiniteTransition(label = "Glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(220.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val brush = Brush.radialGradient(
                colors = listOf(
                    if (isActive) Color(0xFF00BFFF).copy(alpha = glowAlpha) else Color.White.copy(alpha = 0.1f),
                    Color.Transparent
                )
            )
            drawCircle(brush = brush)
        }

        Surface(
            modifier = Modifier.size(140.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.1f),
            border = BorderStroke(3.dp, Color.White.copy(alpha = 0.5f)),
            shadowElevation = 10.dp
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                        )
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isBooting) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(50.dp, 28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isActive) Color(0xFF00BFFF) else Color.Gray.copy(alpha = 0.5f))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(if (isActive) Alignment.CenterEnd else Alignment.CenterStart)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isActive) "DISCONNECT" else "CONNECT",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TactileIgyButton(
    text: String,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    color: Color = Color.Black,
    isActive: Boolean = false,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)

    Surface(
        onClick = { if (!isLoading) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(4.dp),
        color = if (isActive) color else cardBg,
        border = BorderStroke(1.dp, if (isActive) color else wheat),
        shadowElevation = if (isActive) 0.dp else 6.dp,
        interactionSource = remember { MutableInteractionSource() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                if (!isActive) {
                    drawPath(
                        path = Path().apply {
                            moveTo(0f, size.height)
                            lineTo(size.width, size.height)
                            lineTo(size.width, size.height - 4.dp.toPx())
                            lineTo(0f, size.height - 4.dp.toPx())
                            close()
                        },
                        color = Color.Black.copy(alpha = 0.15f)
                    )
                }
            },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = if (isActive) Color.White else Color(0xFF00BFFF),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    text = text,
                    color = if (isActive) Color.White else deepGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun BottomNavItem(label: String, icon: ImageVector, isSelected: Boolean = false, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon, 
            contentDescription = label, 
            tint = if (isSelected) Color(0xFF00BFFF) else Color.White.copy(alpha = 0.7f), 
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label, 
            color = if (isSelected) Color(0xFF00BFFF) else Color.White.copy(alpha = 0.7f), 
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun IgyTactileSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        
        Box(
            modifier = Modifier
                .size(48.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) Color(0xFF00BFFF) else Color.Gray.copy(alpha = 0.3f))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
