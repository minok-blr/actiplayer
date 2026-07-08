package com.flowstate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * FlowState is dark-only by design: the phone is read in sunlight through goggles, so
 * the UI is a near-black ground with mode color as the single saturated channel
 * (RIDING orange / LIFT ice / PAUSED slate). No dynamic color — mode hues must stay exact.
 */
object FlowColors {
    val ground = Color(0xFF0B0E14)
    val surface = Color(0xFF151B26)
    val surfaceHigh = Color(0xFF1B2230)
    val snow = Color(0xFFF2F5F9)
    val dim = Color(0xFF8B96A8)
    val outline = Color(0xFF232C3C)

    val riding = Color(0xFFFF5A1F)
    val lift = Color(0xFF4FC3F7)
    val paused = Color(0xFF7E8AA0)
    val ok = Color(0xFF6FDB8F)
    val hot = Color(0xFFFFB59D)
}

@Composable
fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FlowColors.riding,
            onPrimary = FlowColors.ground,
            secondary = FlowColors.lift,
            onSecondary = FlowColors.ground,
            background = FlowColors.ground,
            onBackground = FlowColors.snow,
            surface = FlowColors.ground,
            onSurface = FlowColors.snow,
            surfaceVariant = FlowColors.surface,
            onSurfaceVariant = FlowColors.dim,
            surfaceContainer = FlowColors.surface,
            surfaceContainerHigh = FlowColors.surfaceHigh,
            outline = FlowColors.outline,
            outlineVariant = FlowColors.outline,
        ),
        content = content,
    )
}
