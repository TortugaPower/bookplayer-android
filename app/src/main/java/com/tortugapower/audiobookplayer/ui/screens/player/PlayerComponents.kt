package com.tortugapower.audiobookplayer.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R
import kotlinx.coroutines.delay

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var containerWidth by remember { mutableStateOf(0) }
    var textWidth by remember { mutableStateOf(0) }

    LaunchedEffect(text, containerWidth, textWidth) {
        if (textWidth > containerWidth && containerWidth > 0) {
            while (true) {
                delay(2000) // Initial wait
                scrollState.animateScrollTo(
                    value = textWidth - containerWidth,
                    animationSpec = tween(
                        durationMillis = (textWidth - containerWidth) * 30,
                        easing = LinearEasing
                    )
                )
                delay(2000) // End wait
                scrollState.scrollTo(0)
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { containerWidth = it.size.width }
            .horizontalScroll(scrollState, enabled = false),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = style,
            onTextLayout = { textWidth = it.size.width },
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier.wrapContentWidth(unbounded = true)
        )
    }
}

@Composable
fun SheetHeaderButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

internal fun formatSpeed(speed: Float): String {
    val s = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${s}x"
}

internal fun formatInterval(context: android.content.Context, seconds: Int): String {
    return when {
        seconds < 60 -> context.getString(R.string.interval_seconds, seconds)
        seconds == 60 -> context.getString(R.string.interval_1_min)
        seconds == 90 -> context.getString(R.string.interval_1_min_30_secs)
        else -> context.getString(R.string.interval_minutes, seconds / 60)
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsRowToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun SettingsRowPicker(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun QuickSpeedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun QuickSpeedLabelButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SeekButton(isForward: Boolean, seconds: Int, onClick: () -> Unit) {
    // TalkBack reads the whole control as "Rewind/Fast forward N seconds" (mirrors iOS VoiceOver),
    // not the bare interval number. The number Text and icon are decorative and merged away.
    val label = stringResource(
        if (isForward) R.string.player_forward_seconds else R.string.player_rewind_seconds,
        seconds
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(8.dp)
            .semantics(mergeDescendants = true) { contentDescription = label }
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Circular seek arrow drawn with a real stroke so we control its (thin) weight — Material's
            // Replay glyph is a fixed ~2dp-weight filled path that reads too thick at this size. Rewind is
            // counter-clockwise; forward is the same drawing mirrored horizontally (like iOS goforward).
            CircularSeekArrow(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 5.dp,
                modifier = Modifier
                    .size(72.dp)
                    .then(if (isForward) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)
            )
            Text(
                // Prefix the direction sign like iOS ("-15" / "+30").
                text = if (isForward) "+$seconds" else "-$seconds",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                // Centered on the circle (= the tail's 9/3-o'clock height), matching iOS, instead of the
                // previous 4dp downward "optical" nudge that made it sit low.
                modifier = Modifier.clearAndSetSemantics {} // announced via the parent's contentDescription
            )
        }
    }
}

/**
 * A thin circular "seek" arrow (rewind direction: counter-clockwise, open at the top with an arrowhead).
 * Drawn with a real [Stroke] so the weight is tunable via [strokeWidth] — unlike the fixed-weight Material
 * Replay glyph. Mirror it horizontally at the call site for the forward variant.
 */
@Composable
private fun CircularSeekArrow(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        // Leave a margin for the arrowhead, which extends past the circle at the top.
        val diameter = size.minDimension - sw * 3f
        val r = diameter / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Three-quarter circle: from 12 o'clock clockwise to 9 o'clock, leaving the upper-left quadrant
        // (9→12) open — the iOS gobackward shape. The tail ends at 9 o'clock; the arrowhead sits at 12.
        drawArc(
            color = color,
            startAngle = -90f, // 12 o'clock
            sweepAngle = 270f, // clockwise → 3, 6, ends at 9 o'clock
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(diameter, diameter),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )

        // Arrowhead at 12 o'clock (the arc's start), a compact triangle pointing HORIZONTALLY left into the
        // open quadrant; its base sits at 12 where the line heads off clockwise.
        val topX = cx
        val topY = cy - r
        val headLen = sw * 3.4f
        val halfH = sw * 2.2f
        drawPath(
            Path().apply {
                moveTo(topX - headLen, topY) // tip, left of 12 o'clock
                lineTo(topX, topY - halfH)   // base upper (at 12)
                lineTo(topX, topY + halfH)   // base lower
                close()
            },
            color = color,
        )
    }
}

@Composable
fun PlayerBottomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    label: String? = null,
    contentDescription: String? = null,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable { onClick() }
            .padding(horizontal = if (label != null) 12.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                // When there's no visible label the icon is the only content, so it must carry the
                // control's accessible name for TalkBack (decorative only when a label is present).
                Icon(
                    imageVector = icon,
                    contentDescription = if (label == null) contentDescription else null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                if (label != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SoundwaveLoadingOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Trigger height change when alpha is at its maximum (darkest point)
    val seed = remember(alpha > 0.79f) { kotlin.random.Random.nextInt() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(10) { i ->
                // Animate height based on seed and index
                val heightScale by animateFloatAsState(
                    targetValue = remember(seed, i) { 0.2f + kotlin.random.Random.nextFloat() * 0.8f },
                    animationSpec = tween(500), // Smooth transition when seed changes
                    label = "height_$i"
                )

                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(heightScale)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    val mStr = if (minutes < 10) "0$minutes" else minutes.toString()
    val sStr = if (seconds < 10) "0$seconds" else seconds.toString()

    return "$mStr:$sStr"
}
