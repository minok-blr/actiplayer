package com.flowstate.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.flowstate.app.R

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

/**
 * Inter, from one variable file (SIL OFL; the licence ships in `assets/licenses`).
 *
 * The redesign's pairing — Instrument Sans for the UI, JetBrains Mono for every label,
 * number and caption, set in capitals with wide tracking — read as a generated mock-up
 * rather than a product: monospaced, spaced-out capitals are a code editor's voice, not a
 * music app's. Inter is what a great many well-made product UIs are set in, it stays
 * legible small and in sunlight, and it has what the monospace was standing in for:
 * true tabular figures, so clocks and counters hold still without a typewriter face.
 *
 * Two optical sizes from the same file. Text sizes use Inter's text cut; big type (the
 * mode name, the countdown, screen titles) uses its display cut, drawn with tighter
 * spacing and finer detail for large sizes — the difference between a headline and body
 * copy blown up.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

/** Inter at text size: everything below ~24 sp. */
val Sans = FontFamily(
    inter(400, 14f),
    inter(500, 14f),
    inter(600, 14f),
    inter(700, 14f),
    inter(800, 14f),
)

/** Inter Display: mode names, the countdown, screen titles, big numbers. */
val Display = FontFamily(
    inter(600, 32f),
    inter(700, 32f),
    inter(800, 32f),
    inter(900, 32f),
)

/**
 * Tabular figures: digits of equal width, so a ticking clock does not shuffle sideways and
 * columns of numbers line up. Merged into a Text's style where numbers tick or align;
 * prose keeps Inter's proportional figures.
 */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * Sentence case, no tracking games. Labels are small semibold text, not spaced capitals;
 * the only capitals left in the app are the mode names, which are meant to be read at a
 * glance from arm's length.
 */
private val ActiTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displayMedium = displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        displaySmall = displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(
            fontFamily = Display,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 30.sp,
            letterSpacing = (-0.6).sp,
        ),
        headlineMedium = headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Sans, fontSize = 16.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Sans, fontSize = 15.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = Sans, fontSize = 13.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.sp,
        ),
        labelSmall = labelSmall.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 0.sp,
        ),
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
