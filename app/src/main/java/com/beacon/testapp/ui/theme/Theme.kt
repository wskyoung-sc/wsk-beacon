package com.beacon.testapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PageBackground = Color(0xFFFFFFFF)
val CardIdle = Color(0xFFF2F2F2)
val Ink = Color(0xFF111111)
val MutedGray = Color(0xFF8A8A8A)
val RadioIdle = Color(0xFFC8C8C8)

private val ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    background = PageBackground,
    surface = CardIdle,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = MutedGray,
)

@Composable
fun BeaconTestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content,
    )
}
