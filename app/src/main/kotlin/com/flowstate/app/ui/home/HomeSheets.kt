package com.flowstate.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.blurb
import com.flowstate.app.ui.glyph
import com.flowstate.app.ui.label
import com.flowstate.data.SettingsStore
import com.flowstate.engine.Activity

/*
 * The sheets off screen B1's rows. The heart-rate one is [HeartRateSetup], shared with
 * screen A4; music source and crossfade moved to the Settings screen (D3), where the
 * design puts them.
 */

/**
 * The activity picker. Every chip is live — each activity has its own profile — and the
 * chips wrap rather than scroll: seven of them off the side of a sheet hid the selected
 * one, so opening the picker could show three chips with none of them marked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityPicker(settings: SettingsStore, onPicked: () -> Unit) {
    val selected by settings.activity.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Activity")
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            Activity.entries.forEach { activity ->
                val isSelected = selected == activity
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) ActiColors.riding else ActiColors.surfaceHigh)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                settings.setActivity(activity)
                                onPicked()
                            },
                        )
                        .padding(horizontal = 14.dp),
                ) {
                    Icon(
                        activity.glyph,
                        contentDescription = null,
                        tint = if (isSelected) ActiColors.ground else ActiColors.dim,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activity.label(),
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) ActiColors.ground else ActiColors.snow,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ActiColors.surface)
                .padding(14.dp),
        ) {
            Text(
                selected.blurb(),
                style = MaterialTheme.typography.bodyMedium,
                color = ActiColors.dim,
            )
        }
    }
}
