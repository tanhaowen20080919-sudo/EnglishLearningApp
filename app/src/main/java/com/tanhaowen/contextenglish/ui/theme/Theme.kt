package com.tanhaowen.contextenglish.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = ForestDark,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = SoftAmber,
    onSecondaryContainer = Color(0xFF4E2C00),
    background = Paper,
    onBackground = Ink,
    surface = WarmWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F3EE),
    onSurfaceVariant = MutedInk,
    outline = Line
)

@Composable
fun ContextEnglishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content
    )
}
