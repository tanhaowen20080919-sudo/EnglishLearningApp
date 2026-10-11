package com.tanhaowen.contextenglish.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tanhaowen.contextenglish.data.AppPalette

private val GreenColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = ForestDark,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = SoftAmber,
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = WarmWhite,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = MutedInk,
    outline = Line
)

private val BlueColors = lightColorScheme(
    primary = Color(0xFF2856D7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EFFF), onPrimaryContainer = Color(0xFF183675),
    secondary = Color(0xFF54627E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEFF8), onSecondaryContainer = Color(0xFF26334B),
    background = Color(0xFFF7F8FC), onBackground = Color(0xFF202633),
    surface = Color.White, onSurface = Color(0xFF202633),
    surfaceVariant = Color(0xFFEEF1F7), onSurfaceVariant = Color(0xFF687183),
    outline = Color(0xFFDCE2ED), outlineVariant = Color(0xFFE7EBF3)
)

@Composable
fun ContextEnglishTheme(palette: AppPalette = AppPalette.BLUE, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if(palette == AppPalette.BLUE) BlueColors else GreenColors,
        content = content
    )
}
