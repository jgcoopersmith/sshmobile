package com.j0ker.sshmobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD98F),
    onPrimary = Color(0xFF05300F),
    surface = Color(0xFF11151C),
    background = Color(0xFF0B0E13),
    surfaceVariant = Color(0xFF1B212B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F7A33),
    surface = Color(0xFFF7F8FA),
    background = Color(0xFFFFFFFF),
)

@Composable
fun SshMobileTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/**
 * Scrollback colours. The desktop hard-codes LightGreen on Black for the
 * terminal and a bluish palette for chat; these are the same choices retuned
 * so the light theme stays readable.
 */
@Composable
fun colorFor(kind: LineKind, dark: Boolean): Color = when (kind) {
    LineKind.Output -> if (dark) Color(0xFF9CE79C) else Color(0xFF1B3D1B)
    LineKind.Self -> if (dark) Color(0xFFFFF3A8) else Color(0xFF7A5B00)
    LineKind.Peer -> if (dark) Color(0xFF87CEEB) else Color(0xFF176B8A)
    LineKind.System -> if (dark) Color(0xFFFFD24A) else Color(0xFF8A6100)
    LineKind.Error -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
}

/** The terminal keeps its own near-black canvas in both themes, as on the desktop. */
val TerminalBackground = Color(0xFF0A0C0F)
