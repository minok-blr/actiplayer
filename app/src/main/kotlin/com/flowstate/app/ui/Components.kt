package com.flowstate.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flowstate.data.AlbumArtLoader

/** Big-app screen header: heavy title, quiet subtitle, optional trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ActiColors.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}

/** Energy hue for a rating: chill ice -> neutral slate -> send-it orange. */
fun energyTint(rating: Int?): Color = when (rating) {
    1, 2 -> ActiColors.lift
    3 -> ActiColors.paused
    4, 5 -> ActiColors.riding
    else -> ActiColors.surfaceHigh
}

/**
 * The album-art loader, provided once at the top of the tree.
 *
 * A composition local rather than a parameter on six screens: it is a cross-cutting UI
 * service with no per-screen configuration, which is exactly what image loaders use this
 * mechanism for.
 */
val LocalAlbumArt = staticCompositionLocalOf<AlbumArtLoader> {
    error("LocalAlbumArt not provided")
}

/**
 * Embedded cover art for a track, or null while loading or when it has none. Keyed on the
 * id so a recycled row never shows the previous track's cover.
 */
@Composable
fun rememberAlbumArt(mediaStoreId: Long?): ImageBitmap? {
    val loader = LocalAlbumArt.current
    var art by remember(mediaStoreId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mediaStoreId) {
        art = mediaStoreId?.let { loader.load(it)?.asImageBitmap() }
    }
    return art
}

/**
 * Artwork for a track: the real cover when the file carries one, otherwise a generated
 * tile in the track's energy colour. Never an empty square — about half a real library
 * has no embedded art, and a grey hole in every other row is what makes a music app feel
 * dead.
 */
@Composable
fun ArtTile(
    tint: Color,
    size: Dp = 44.dp,
    icon: ImageVector = Icons.Rounded.MusicNote,
    art: ImageBitmap? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 5))
            .background(
                Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.12f))),
            ),
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = ActiColors.snow.copy(alpha = 0.75f),
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/** The 1–5 energy rating control used everywhere a track can be rated. */
@Composable
fun RatingBoxes(rating: Int?, box: Dp = 30.dp, onRate: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { value ->
            val selected = rating == value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(box)
                    .clip(RoundedCornerShape(box / 4))
                    .background(if (selected) energyTint(value) else ActiColors.surfaceHigh)
                    .clickable { onRate(value) },
            ) {
                Text(
                    "$value",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Normal,
                    color = if (selected) ActiColors.ground else ActiColors.dim,
                )
            }
        }
    }
}

/**
 * Every bottom sheet in the app. Two things Material's default gets wrong on a dark-only
 * app: content colour is derived from the container, and on our `surface` that resolves
 * to the dim grey — which dimmed every line of text in the sheet *and*, because the sheet
 * picks its system-bar icons from that colour's luminance, turned the status-bar icons
 * dark on a dark screen and painted a white navigation bar under the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiBottomSheet(
    onDismiss: () -> Unit,
    containerColor: Color = ActiColors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        contentColor = ActiColors.snow,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = false,
            isAppearanceLightNavigationBars = false,
        ),
        content = content,
    )
}

/**
 * The one stepper: − value +. Buttons are 48 dp by default (plan §5's floor off-session)
 * and centre their glyph in a box, so a large font scale grows the glyph rather than
 * pushing it out of its button the way top-padding centring did.
 */
@Composable
fun Stepper(
    value: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = ActiColors.snow,
    buttonSize: Dp = 48.dp,
    valueWidth: Dp = 64.dp,
    valueSize: TextUnit = 22.sp,
    label: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        StepperButton("−", "Decrease${label?.let { " $it" }.orEmpty()}", buttonSize, onDown)
        Text(
            value,
            fontFamily = Display,
            style = LocalTextStyle.current.merge(Tabular),
            fontSize = valueSize,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(valueWidth),
        )
        StepperButton("+", "Increase${label?.let { " $it" }.orEmpty()}", buttonSize, onUp)
    }
}

/**
 * Holding a stepper button repeats it, as steppers do everywhere else: getting a max
 * heart rate from 190 to 176, or a rest from 1:30 to 0:45, should not take fifteen taps.
 */
@Composable
private fun StepperButton(glyph: String, description: String, size: Dp, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) ActiColors.outline else ActiColors.surfaceHigh)
            .semantics {
                role = Role.Button
                onClick(label = description) {
                    latest()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        latest()
                        val repeat = scope.launch {
                            delay(REPEAT_AFTER_MS)
                            while (true) {
                                latest()
                                delay(REPEAT_EVERY_MS)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                        pressed = false
                    },
                )
            },
    ) {
        Text(glyph, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ActiColors.snow)
    }
}

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 90L

/** A row of mutually exclusive pills (sensitivity, units, music source). */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.selectableGroup()) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) ActiColors.snow else ActiColors.surfaceHigh)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    label(option),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    color = if (isSelected) ActiColors.ground else ActiColors.snow,
                )
            }
        }
    }
}

/**
 * A section heading: small semibold sentence case in the muted colour — how a real app
 * labels a group, rather than spaced-out monospace capitals.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = ActiColors.dim) {
    Text(
        text,
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = color,
        modifier = modifier,
    )
}

/** Secondary detail under a title: plain small text, tabular so counts line up. */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ActiColors.dim,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        fontFamily = Sans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        style = LocalTextStyle.current.merge(Tabular),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** "‹ History": the back link every sub-screen opens with. 48 dp tall, as a target. */
@Composable
fun BackLink(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "‹ $label",
        color = ActiColors.dim,
        fontSize = 16.sp,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Back to $label", onClick = onBack)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(end = 12.dp),
    )
}

/**
 * A length of time for reading, not for a clock face: "48s", "2m 46s", "12m", "3h 12m".
 *
 * History and the recap used `M:SS` under an hour and `H:MM` above it, so "2:46" meant
 * two minutes on one row and two hours on the next. Units remove the guess.
 */
fun Long.spokenDuration(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m >= 10 -> "${m}m"
        m > 0 && s > 0 -> "${m}m ${s}s"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** Title and artist on one line, leaving out whatever is missing. */
fun joinNonEmpty(vararg parts: String?, separator: String = " · "): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(separator)

