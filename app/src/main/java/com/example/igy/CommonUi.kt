package com.example.igy

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VroomEngineTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    
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
fun VroomBackground(isDarkMode: Boolean, content: @Composable BoxScope.() -> Unit) {
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
                // Drawing a subtle network pattern
                val strokeWidth = 1.dp.toPx()
                val dotRadius = 2.dp.toPx()
                val color = (if (isDarkMode) Color(0xFF1E90FF) else Color(0xFF4682B4)).copy(alpha = 0.1f)
                
                // Static points for network (reproducible for drawing)
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
fun VroomCircularButton(
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
        // Outer Glow Ring
        Canvas(modifier = Modifier.size(200.dp)) {
            val brush = Brush.radialGradient(
                colors = listOf(
                    if (isActive) Color(0xFF00BFFF).copy(alpha = glowAlpha) else Color.White.copy(alpha = 0.1f),
                    Color.Transparent
                )
            )
            drawCircle(brush = brush)
        }

        // Button Surface (Glassmorphism)
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
                        // Toggle Icon (Switch like in the image)
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
fun MatrixTab(
    text: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.Black,
    onClick: () -> Unit
) {
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)

    // Tactile 3D Tab
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) cardBg else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, wheat) else null,
        shadowElevation = if (isSelected) 2.dp else 0.dp,
        interactionSource = remember { MutableInteractionSource() } // No ripple by default if not provided to Button/Surface with indication
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = text,
                color = if (isSelected) (if (isDarkMode) Color.White else Color.Black) else deepGray.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun TactileVroomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    color: Color = Color.Black,
    isActive: Boolean = false
) {
    val creamColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFFDF5E6)
    val cardBg = if (isDarkMode) Color(0xFF2D2D2D) else Color.White
    val deepGray = if (isDarkMode) Color.White else Color(0xFF2F4F4F)
    val wheat = if (isDarkMode) Color(0xFF333333) else Color(0xFFF5DEB3)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(4.dp), // Sharper, more mechanical feel
        color = if (isActive) color else cardBg,
        border = BorderStroke(1.dp, if (isActive) color else wheat),
        shadowElevation = if (isActive) 0.dp else 6.dp, // Solid 3D shadow effect
        interactionSource = remember { MutableInteractionSource() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize().drawBehind {
                if (!isActive) {
                    // Solid 3D bottom shadow
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
