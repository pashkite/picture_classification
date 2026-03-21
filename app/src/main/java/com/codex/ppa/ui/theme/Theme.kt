package com.codex.ppa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0ECEE),
    onPrimaryContainer = Color(0xFF0C1F21),
    secondary = Color(0xFF7A4F38),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9E0D1),
    onSecondaryContainer = Color(0xFF2C170B),
    surface = Color(0xFFFFFBF6),
    surfaceVariant = Color(0xFFE6E1DA),
    background = Color(0xFFF6F1EA)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD0D5),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF184B4F),
    onPrimaryContainer = Color(0xFFD0ECEE),
    secondary = Color(0xFFECC1A9),
    onSecondary = Color(0xFF452A19),
    secondaryContainer = Color(0xFF5F3A26),
    onSecondaryContainer = Color(0xFFF9E0D1),
    surface = Color(0xFF161412),
    surfaceVariant = Color(0xFF4A4641),
    background = Color(0xFF11100E)
)

@Composable
fun PersonalMediaSorterTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(),
        content = content
    )
}
