package com.example.fleetstrike.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The naval-chart palette used across the app. */
object Sea {
    val Night = Color(0xFF0B1A2E)
    val Deep = Color(0xFF0F2742)
    val Panel = Color(0xFF14304F)
    val PanelHigh = Color(0xFF1B3D63)
    val OceanTop = Color(0xFF1A5486)
    val OceanBottom = Color(0xFF0F3A62)
    val GridLine = Color(0xFF4F8CC0)
    val Label = Color(0xFF8FB4D8)
    val Text = Color(0xFFE8F1FA)
    val TextDim = Color(0xFF9DB6CF)
    val Amber = Color(0xFFFFB547)
    val Sonar = Color(0xFF3DDC97)
    val Hit = Color(0xFFFF5A3C)
    val Flame = Color(0xFFFFD166)
    val Miss = Color(0xFFE6F1FF)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun FleetStrikeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sea.Amber,
            onPrimary = Color(0xFF2A1A00),
            secondary = Sea.Sonar,
            onSecondary = Color(0xFF00281A),
            background = Sea.Night,
            onBackground = Sea.Text,
            surface = Sea.Panel,
            onSurface = Sea.Text,
            surfaceVariant = Sea.PanelHigh,
            onSurfaceVariant = Sea.TextDim,
            surfaceContainer = Sea.Panel,
            surfaceContainerHigh = Sea.PanelHigh,
            surfaceContainerHighest = Sea.PanelHigh,
            outline = Sea.GridLine,
            error = Sea.Danger,
        ),
        content = content,
    )
}
