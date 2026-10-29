package com.flowstate.app.ui.session

import androidx.compose.material3.LocalTextStyle
import com.flowstate.app.ui.Tabular
import com.flowstate.app.ui.BackLink
import com.flowstate.app.ui.Stepper
import com.flowstate.app.ui.header
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Display
import com.flowstate.app.ui.SectionLabel
import com.flowstate.data.SettingsStore
import com.flowstate.engine.Activity
import com.flowstate.engine.TimerPlan

/**
 * Screen D4 — the work/rest clock, chosen before a Strength or HIIT session starts.
 *
 * Indoors there is no GPS and no altitude, and lifting barely moves the phone, so no
 * amount of signal processing would beat simply knowing the plan. This screen is where
 * the athlete supplies it.
 */
@Composable
fun TimerSetupScreen(
    activity: Activity,
    settings: SettingsStore,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    // Each clock activity keeps its own plan: a Tabata set up for HIIT no longer replaces
    // the Strength 30/90 the next gym session expects.
    val stored by settings.timerPlan(activity).collectAsStateWithLifecycle()
    var work by remember(stored) { mutableStateOf(stored.first) }
    var rest by remember(stored) { mutableStateOf(stored.second) }
    var rounds by remember(stored) { mutableStateOf(stored.third) }

    fun persist() = settings.setTimerPlan(activity, work, rest, rounds)

    val plan = TimerPlan(work, rest, rounds)

    Column(
        Modifier
            .fillMaxSize()
            .background(ActiColors.ground)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
        ) {
            BackLink("Home", onBack)
            Spacer(Modifier.weight(1f))
            Text(
                activity.header(),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ActiColors.riding,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Work / rest clock",
            style = MaterialTheme.typography.headlineLarge,
            color = ActiColors.snow,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Indoors there's no GPS or altitude, so the clock drives the music: work gets " +
                "Hype, rest gets Chill. Tap the screen to end a set early.",
            style = MaterialTheme.typography.bodyMedium,
            color = ActiColors.dim,
        )
        Spacer(Modifier.height(18.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TimerPlan.presets.chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(bottom = 10.dp),
                ) {
                    row.forEach { (label, preset) ->
                        PresetCard(
                            label = label,
                            preset = preset,
                            selected = preset == plan,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            work = preset.workMs
                            rest = preset.restMs
                            rounds = preset.rounds
                            settings.setTimerPlan(activity, work, rest, rounds)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(ActiColors.surface)
                    .padding(16.dp),
            ) {
                SectionLabel("Custom")
                Spacer(Modifier.height(10.dp))
                StepperRow("Work", work.clock(), ActiColors.riding,
                    onDown = { work = (work - 5_000).coerceAtLeast(5_000); persist() },
                    onUp = { work = (work + 5_000).coerceAtMost(600_000); persist() })
                StepperRow("Rest", rest.clock(), ActiColors.lift,
                    onDown = { rest = (rest - 5_000).coerceAtLeast(0); persist() },
                    onUp = { rest = (rest + 5_000).coerceAtMost(600_000); persist() })
                StepperRow("Rounds", "$rounds", ActiColors.snow,
                    onDown = { rounds = (rounds - 1).coerceAtLeast(1); persist() },
                    onUp = { rounds = (rounds + 1).coerceAtMost(60); persist() })
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "Start · ${plan.totalMs.spokenMinutes()}",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.merge(Tabular),
            textAlign = TextAlign.Center,
            color = ActiColors.ground,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ActiColors.riding)
                .clickable {
                    persist()
                    onStart()
                }
                .padding(vertical = 22.dp),
        )
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun PresetCard(
    label: String,
    preset: TimerPlan,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ActiColors.surface)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) ActiColors.snow else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onSelect)
            .padding(14.dp),
    ) {
        Text(label, fontFamily = Display, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ActiColors.snow)
        Spacer(Modifier.height(2.dp))
        Caption(preset.summary())
    }
}

/** 56 dp buttons: this screen is used with chalk on the hands, or gloves. */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    valueColor: Color,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = ActiColors.snow, modifier = Modifier.weight(1f))
        Stepper(
            value = value,
            onDown = onDown,
            onUp = onUp,
            valueColor = valueColor,
            buttonSize = 56.dp,
            valueWidth = 84.dp,
            valueSize = 22.sp,
            label = label.lowercase(),
        )
    }
}

private fun TimerPlan.summary(): String {
    val work = "${workMs / 1000} s"
    return if (restMs == 0L) {
        "Every $work\n$rounds rounds"
    } else {
        "$work on, ${restMs / 1000} s off\n$rounds rounds"
    }
}

private fun Long.clock(): String {
    val total = this / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun Long.spokenMinutes(): String {
    val minutes = (this / 60_000).toInt()
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}min"
        // A custom plan can be shorter than a minute; "0 min" would read like a bug.
        minutes == 0 -> "${this / 1000} s"
        else -> "$minutes min"
    }
}
