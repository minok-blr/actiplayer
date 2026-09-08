package com.flowstate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ActiPlayer is dark-only by design: the phone is read in sunlight through goggles, so
 * the UI is a near-black ground with mode color as the single saturated channel
 * (RIDING orange / LIFT ice / PAUSED slate). No dynamic color — mode hues must stay exact.
 */
object ActiColors {
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

    /** Service accents — used only to badge the services themselves. */
    val spotify = Color(0xFF1DB954)
    val soundcloud = Color(0xFFFF7700)
}

/** Big-music-app typography: heavy display faces, tight tracking, calm body text. */
private val ActiTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Black),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.copy(letterSpacing = 1.5.sp),
    )
}

@Composable
fun ActiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ActiColors.riding,
            onPrimary = ActiColors.ground,
            secondary = ActiColors.lift,
            onSecondary = ActiColors.ground,
            background = ActiColors.ground,
            onBackground = ActiColors.snow,
            surface = ActiColors.ground,
            onSurface = ActiColors.snow,
            surfaceVariant = ActiColors.surface,
            onSurfaceVariant = ActiColors.dim,
            surfaceContainer = ActiColors.surface,
            surfaceContainerHigh = ActiColors.surfaceHigh,
            outline = ActiColors.outline,
            outlineVariant = ActiColors.outline,
        ),
        typography = ActiTypography,
        content = content,
    )
}
