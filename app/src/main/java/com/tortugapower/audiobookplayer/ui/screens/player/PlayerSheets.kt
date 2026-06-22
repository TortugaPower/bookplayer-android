package com.tortugapower.audiobookplayer.ui.screens.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.ui.components.BookPlayerSlider
import com.tortugapower.audiobookplayer.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersListSheet(
    viewModel: PlayerViewModel,
    absolutePosition: Long,
    onDismiss: () -> Unit
) {
    val chapters by viewModel.chapters.collectAsState()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(40.dp)) // Spacer
                Text(
                    text = stringResource(R.string.player_chapters_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SheetHeaderButton(text = stringResource(R.string.common_done), onClick = onDismiss)
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(chapters) { chapter ->
                    val isPlaying = absolutePosition >= (chapter.start * 1000) &&
                                    absolutePosition < ((chapter.start + chapter.duration) * 1000)

                    Surface(
                        onClick = { viewModel.seekToChapter(chapter) },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = stringResource(
                                        R.string.player_chapter_details,
                                        formatTime((chapter.start * 1000).toLong()),
                                        formatTime((chapter.duration * 1000).toLong())
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            if (isPlaying) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreOptionsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BookmarkDialogButton(text = stringResource(R.string.player_chapters_title)) {
                viewModel.showMoreOptions = false
                viewModel.showChaptersList = true
            }
            BookmarkDialogButton(text = stringResource(R.string.player_jump_to_start)) {
                viewModel.jumpToStart()
            }
            BookmarkDialogButton(text = currentItem?.let { if (it.isFinished) stringResource(R.string.player_mark_as_unfinished) else stringResource(R.string.player_mark_as_finished) } ?: stringResource(R.string.player_mark_as_finished)) {
                viewModel.toggleFinished()
            }
            BookmarkDialogButton(text = if (viewModel.isRepeatEnabled) stringResource(R.string.player_repeat_off) else stringResource(R.string.player_repeat_on)) {
                viewModel.toggleRepeat()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksListSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_bookmarks_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                stringResource(R.string.player_bookmarks_manual),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookmarks) { bookmark ->
                    Surface(
                        onClick = { viewModel.seekToBookmark(bookmark) },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime((bookmark.time * 1000).toLong()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (bookmark.note != null && bookmark.note!!.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = bookmark.note!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onMoreClick: () -> Unit
) {
    val context = LocalContext.current
    var currentSpeed by remember { mutableStateOf(viewModel.playbackSpeed.value) }
    var currentVolume by remember { mutableStateOf(viewModel.playbackVolume.value) }
    val volumeBoost by viewModel.volumeBoost.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SheetHeaderButton(text = stringResource(R.string.common_more), onClick = onMoreClick)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.player_set_speed), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (currentSpeed % 1.0f == 0.0f) currentSpeed.toInt() else currentSpeed}x",
                    fontWeight = FontWeight.Bold
                )
            }

            BookPlayerSlider(
                value = currentSpeed,
                onValueChange = {
                    currentSpeed = it
                    viewModel.setPlaybackSpeed(context, it)
                },
                valueRange = 0.5f..4.0f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.player_speed_min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(stringResource(R.string.player_speed_max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickSpeedButton(icon = Icons.Default.Remove) {
                    currentSpeed = (currentSpeed - 0.1f).coerceAtLeast(0.5f)
                    viewModel.setPlaybackSpeed(context, currentSpeed)
                }
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction1), currentSpeed == viewModel.quickAction1) {
                    currentSpeed = viewModel.quickAction1
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction1)
                }
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction2), currentSpeed == viewModel.quickAction2) {
                    currentSpeed = viewModel.quickAction2
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction2)
                }
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction3), currentSpeed == viewModel.quickAction3) {
                    currentSpeed = viewModel.quickAction3
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction3)
                }
                QuickSpeedButton(icon = Icons.Default.Add) {
                    currentSpeed = (currentSpeed + 0.1f).coerceAtMost(4.0f)
                    viewModel.setPlaybackSpeed(context, currentSpeed)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.player_volume), style = MaterialTheme.typography.bodyLarge)
            BookPlayerSlider(
                value = currentVolume,
                onValueChange = {
                    currentVolume = it
                    viewModel.setPlaybackVolume(context, it)
                },
                valueRange = 0.0f..1.0f
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_boost_volume), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.player_boost_volume_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = volumeBoost,
                    onCheckedChange = { viewModel.toggleVolumeBoost(context) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSleepTimerPicker(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    var selectedHours by remember { mutableIntStateOf(1) }
    var selectedMinutes by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_custom_timer),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeWheelPicker(
                    range = 0..23,
                    selectedValue = selectedHours,
                    onValueChange = { selectedHours = it },
                    label = stringResource(R.string.player_timer_hours),
                    padToTwoDigits = false,
                    modifier = Modifier.weight(1f)
                )
                TimeWheelPicker(
                    range = 0..59,
                    selectedValue = selectedMinutes,
                    onValueChange = { selectedMinutes = it },
                    label = stringResource(R.string.player_timer_min),
                    padToTwoDigits = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val totalMinutes = (selectedHours * 60) + selectedMinutes
                    if (totalMinutes > 0) {
                        viewModel.startSleepTimer(totalMinutes)
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.player_start_timer), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimeWheelPicker(
    range: IntRange,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    padToTwoDigits: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedValue
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Update selected value based on scroll
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            onValueChange(listState.firstVisibleItemIndex)
        }
    }

    Box(
        modifier = modifier.height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        // Selection highlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 55.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items((range.last - range.first + 1)) { index ->
                val value = range.first + index
                val isSelected = value == selectedValue
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (!padToTwoDigits) value.toString() else (if (value < 10) "0$value" else value.toString()),
                        style = if (isSelected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Label (e.g., "hours", "min") positioned to the right of the numbers
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_sleep_timer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            val options = listOf(
                stringResource(R.string.player_timer_off) to 0,
                stringResource(R.string.player_timer_5m) to 5,
                stringResource(R.string.player_timer_10m) to 10,
                stringResource(R.string.player_timer_15m) to 15,
                stringResource(R.string.player_timer_30m) to 30,
                stringResource(R.string.player_timer_45m) to 45,
                stringResource(R.string.player_timer_1h) to 60,
                stringResource(R.string.player_timer_end_chapter) to -1
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (label, minutes) ->
                    BookmarkDialogButton(
                        text = label,
                        onClick = {
                            if (minutes == 0) {
                                viewModel.stopSleepTimer()
                            } else if (minutes == -1) {
                                // TODO: Implement End of Chapter
                                onDismiss()
                            } else {
                                viewModel.startSleepTimer(minutes)
                            }
                        }
                    )
                }

                BookmarkDialogButton(text = stringResource(R.string.player_timer_custom)) {
                    viewModel.toggleSleepTimerMenu()
                    viewModel.toggleCustomSleepTimerPicker()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendedControlsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val volumeBoost by viewModel.volumeBoost.collectAsStateWithLifecycle()

    var showRewindPicker by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }
    var showSmartRewindPicker by remember { mutableStateOf(false) }
    var showListActionPicker by remember { mutableStateOf(false) }
    var showSpeedPicker1 by remember { mutableStateOf(false) }
    var showSpeedPicker2 by remember { mutableStateOf(false) }
    var showSpeedPicker3 by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings(context)
    }

    if (showRewindPicker) {
        IntervalPickerDialog(
            title = stringResource(R.string.player_rewind_interval_title),
            currentValue = viewModel.rewindInterval,
            onValueSelected = {
                viewModel.updateRewindInterval(context, it)
                showRewindPicker = false
            },
            onDismiss = { showRewindPicker = false }
        )
    }

    if (showForwardPicker) {
        IntervalPickerDialog(
            title = stringResource(R.string.player_forward_interval_title),
            currentValue = viewModel.forwardInterval,
            onValueSelected = {
                viewModel.updateForwardInterval(context, it)
                showForwardPicker = false
            },
            onDismiss = { showForwardPicker = false }
        )
    }

    if (showSmartRewindPicker) {
        IntervalPickerDialog(
            title = stringResource(R.string.player_smart_rewind_limit_title),
            currentValue = viewModel.smartRewindLimit,
            onValueSelected = {
                viewModel.updateSmartRewindLimit(context, it)
                showSmartRewindPicker = false
            },
            onDismiss = { showSmartRewindPicker = false }
        )
    }

    if (showListActionPicker) {
        OptionsPickerDialog(
            title = stringResource(R.string.player_list_button_action_title),
            options = listOf("Chapters", "Bookmarks"),
            currentValue = viewModel.listButtonOpens,
            onValueSelected = {
                viewModel.updateListButtonOpens(context, it)
                showListActionPicker = false
            },
            onDismiss = { showListActionPicker = false }
        )
    }

    if (showSpeedPicker1) {
        SpeedPickerDialog(
            title = stringResource(R.string.player_quick_action_1),
            currentValue = viewModel.quickAction1,
            onValueSelected = {
                viewModel.updateQuickAction1(context, it)
                showSpeedPicker1 = false
            },
            onDismiss = { showSpeedPicker1 = false }
        )
    }

    if (showSpeedPicker2) {
        SpeedPickerDialog(
            title = stringResource(R.string.player_quick_action_2),
            currentValue = viewModel.quickAction2,
            onValueSelected = {
                viewModel.updateQuickAction2(context, it)
                showSpeedPicker2 = false
            },
            onDismiss = { showSpeedPicker2 = false }
        )
    }

    if (showSpeedPicker3) {
        SpeedPickerDialog(
            title = stringResource(R.string.player_quick_action_3),
            currentValue = viewModel.quickAction3,
            onValueSelected = {
                viewModel.updateQuickAction3(context, it)
                showSpeedPicker3 = false
            },
            onDismiss = { showSpeedPicker3 = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSectionLabel(stringResource(R.string.player_settings_skip_intervals))
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker(stringResource(R.string.player_settings_rewind), formatInterval(context,
viewModel.rewindInterval)) { showRewindPicker = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_settings_forward), formatInterval(context,
viewModel.forwardInterval)) { showForwardPicker = true }
                }
            }
            Text(
                stringResource(R.string.player_settings_skip_intervals_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle(stringResource(R.string.player_settings_smart_rewind), viewModel.smartRewind) {
                        viewModel.updateSmartRewind(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_settings_smart_rewind_limit), formatInterval(context,
viewModel.smartRewindLimit)) { showSmartRewindPicker = true }
                }
            }
            Text(
                stringResource(R.string.player_settings_smart_rewind_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_settings_auto_sleep_timer), viewModel.autoSleep) {
                    viewModel.updateAutoSleep(context, it)
                }
            }
            Text(
                stringResource(R.string.player_settings_auto_sleep_timer_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_boost_volume), volumeBoost) {
                    viewModel.toggleVolumeBoost(context)
                }
            }
            Text(
                stringResource(R.string.player_boost_volume_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            SettingsSectionLabel(stringResource(R.string.player_settings_speed))
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker(stringResource(R.string.player_quick_action_1), formatSpeed(viewModel.quickAction1)) { showSpeedPicker1 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_quick_action_2), formatSpeed(viewModel.quickAction2)) { showSpeedPicker2 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_quick_action_3), formatSpeed(viewModel.quickAction3)) { showSpeedPicker3 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(stringResource(R.string.player_settings_global_speed), viewModel.globalSpeed) {
                        viewModel.updateGlobalSpeed(context, it)
                    }
                }
            }
            Text(
                stringResource(R.string.player_settings_global_speed_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_settings_progress_bar_seeking), viewModel.progressBarSeeking) {
                    viewModel.updateProgressBarSeeking(context, it)
                }
            }
            Text(
                stringResource(R.string.player_settings_progress_bar_seeking_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowPicker(stringResource(R.string.player_settings_list_opens), viewModel.listButtonOpens) { showListActionPicker = true }
            }
            Text(
                stringResource(R.string.player_settings_list_opens_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            SettingsSectionLabel(stringResource(R.string.player_settings_progress_labels))
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle(stringResource(R.string.player_settings_use_remaining_time), viewModel.useRemainingTime) {
                        viewModel.updateUseRemainingTime(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(stringResource(R.string.player_settings_use_chapter_context), viewModel.useChapterContext) {
                        viewModel.updateUseChapterContext(context, it)
                    }
                }
            }
            Text(
                stringResource(R.string.player_settings_progress_labels_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 32.dp)
            )
        }
    }
}
