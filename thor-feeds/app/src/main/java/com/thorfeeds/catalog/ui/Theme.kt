package com.thorfeeds.catalog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF10140F)
private val Panel = Color(0xFF1A2218)
private val PanelHigh = Color(0xFF243026)
private val Brass = Color(0xFFE2B657)
private val BrassInk = Color(0xFF2A2108)
private val Sage = Color(0xFF9EBEA6)
private val Paper = Color(0xFFE7EEE4)
private val Muted = Color(0xFFB7C3B4)
private val Clay = Color(0xFFE07A5F)

private val ThorColors = darkColorScheme(
    primary = Brass,
    onPrimary = BrassInk,
    secondary = Sage,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Panel,
    onSurface = Paper,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    error = Clay,
    onError = Ink,
)

@Composable
fun ThorFeedsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ThorColors, content = content)
}
