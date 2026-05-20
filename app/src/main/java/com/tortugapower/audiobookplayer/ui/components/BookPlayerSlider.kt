package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

@Composable
fun BookPlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        valueRange = valueRange,
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                // Slider has internal padding for the thumb.
                // We expand the constraints and place it with an offset to "break out"
                // and align the track with the parent's edges.
                val horizontalOverflow = 8.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(maxWidth = constraints.maxWidth + (horizontalOverflow * 2))
                )
                layout(placeable.width - (horizontalOverflow * 2), placeable.height) {
                    placeable.place(-horizontalOverflow, 0)
                }
            },
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = remember { MutableInteractionSource() },
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary),
                thumbSize = DpSize(20.dp, 20.dp)
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(6.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                ),
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null
            )
        }
    )
}
