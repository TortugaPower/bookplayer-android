package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.tortugapower.audiobookplayer.wear.R

/** Sleep-timer preset minutes offered on the watch. */
private val SLEEP_PRESETS_MINUTES = listOf(5, 15, 30, 45, 60)

/** "More" controls: playback speed, sleep timer, and volume boost — mirrors iOS's PlaybackControlsView. */
@Composable
fun PlaybackControlsScreen(
    state: RemoteUiState,
    onDecreaseSpeed: () -> Unit,
    onIncreaseSpeed: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSleepOff: () -> Unit,
    onSleepEndOfChapter: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onToggleBoost: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScrollScaffold(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            // Speed: minus / current-rate (tap to cycle) / plus.
            item {
                Text(stringResource(R.string.wear_speed), style = MaterialTheme.typography.caption1)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val decreaseLabel = stringResource(R.string.wear_decrease_speed)
                    val increaseLabel = stringResource(R.string.wear_increase_speed)
                    CompactButton(
                        onClick = onDecreaseSpeed,
                        modifier = Modifier.semantics { contentDescription = decreaseLabel },
                    ) { Text("−") }
                    Chip(
                        onClick = onCycleSpeed,
                        label = { Text(stringResource(R.string.wear_speed_format, state.speed)) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                    CompactButton(
                        onClick = onIncreaseSpeed,
                        modifier = Modifier.semantics { contentDescription = increaseLabel },
                    ) { Text("+") }
                }
            }

            // Volume boost (after speed, before sleep timer).
            item {
                ToggleChip(
                    checked = state.boostVolume,
                    onCheckedChange = { onToggleBoost() },
                    label = { Text(stringResource(R.string.wear_boost_volume)) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(state.boostVolume),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }

            // Sleep timer (last).
            item {
                Text(
                    stringResource(R.string.wear_sleep_timer),
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Chip(
                    onClick = onSleepOff,
                    label = { Text(stringResource(R.string.wear_sleep_off)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onSleepEndOfChapter,
                    label = { Text(stringResource(R.string.wear_sleep_end_of_chapter)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(SLEEP_PRESETS_MINUTES) { minutes ->
                Chip(
                    onClick = { onSleepMinutes(minutes) },
                    label = { Text(stringResource(R.string.wear_sleep_minutes, minutes)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
