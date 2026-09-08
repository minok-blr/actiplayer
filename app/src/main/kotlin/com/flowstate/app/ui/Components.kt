package com.flowstate.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * Local files have no cover art, so every list row gets a generated "artwork" tile:
 * a gradient of the track's energy color. Unrated tracks stay neutral — a subtle nudge
 * that they still need a rating.
 */
@Composable
fun ArtTile(
    tint: Color,
    size: Dp = 44.dp,
    icon: ImageVector = Icons.Rounded.MusicNote,
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
        Icon(
            icon,
            contentDescription = null,
            tint = ActiColors.snow.copy(alpha = 0.75f),
            modifier = Modifier.size(size / 2),
        )
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
