package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.ui.screens.player.PlayerControlsSettingsContent
import com.tortugapower.audiobookplayer.ui.screens.player.SettingsRowToggle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val rewindInterval by remember { PlaybackSettingsManager.getRewindInterval(context) }.collectAsState(initial = 30)
    val forwardInterval by remember { PlaybackSettingsManager.getForwardInterval(context) }.collectAsState(initial = 30)
    val smartRewind by remember { PlaybackSettingsManager.getSmartRewind(context) }.collectAsState(initial = true)
    val smartRewindLimit by remember { PlaybackSettingsManager.getSmartRewindLimit(context) }.collectAsState(initial = 30)
    val autoSleep by remember { PlaybackSettingsManager.getAutoSleepTimer(context) }.collectAsState(initial = false)
    val volumeBoost by remember { PlaybackSettingsManager.getVolumeBoost(context) }.collectAsState(initial = false)
    val quickAction1 by remember { PlaybackSettingsManager.getQuickAction1(context) }.collectAsState(initial = 1.0f)
    val quickAction2 by remember { PlaybackSettingsManager.getQuickAction2(context) }.collectAsState(initial = 2.0f)
    val quickAction3 by remember { PlaybackSettingsManager.getQuickAction3(context) }.collectAsState(initial = 3.0f)
    val globalSpeed by remember { PlaybackSettingsManager.getGlobalSpeedControl(context) }.collectAsState(initial = false)
    val progressBarSeeking by remember { PlaybackSettingsManager.getProgressBarSeeking(context) }.collectAsState(initial = true)
    val listButtonOpens by remember { PlaybackSettingsManager.getListButtonOpens(context) }.collectAsState(initial = PlaybackSettingsManager.LIST_OPENS_CHAPTERS)
    val useRemainingTime by remember { PlaybackSettingsManager.getUseRemainingTime(context) }.collectAsState(initial = true)
    val useChapterContext by remember { PlaybackSettingsManager.getUseChapterContext(context) }.collectAsState(initial = false)

    BookPlayerTabScaffold(
        title = stringResource(R.string.player_controls_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
                .padding(
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp + LocalMiniPlayerInset.current
                )
                .verticalScroll(rememberScrollState())
        ) {
            PlayerControlsSettingsContent(
                rewindInterval = rewindInterval,
                forwardInterval = forwardInterval,
                smartRewind = smartRewind,
                smartRewindLimit = smartRewindLimit,
                autoSleep = autoSleep,
                volumeBoost = volumeBoost,
                quickAction1 = quickAction1,
                quickAction2 = quickAction2,
                quickAction3 = quickAction3,
                globalSpeed = globalSpeed,
                progressBarSeeking = progressBarSeeking,
                listButtonOpens = listButtonOpens,
                useRemainingTime = useRemainingTime,
                useChapterContext = useChapterContext,
                onUpdateRewindInterval = { scope.launch { PlaybackSettingsManager.setRewindInterval(context, it) } },
                onUpdateForwardInterval = { scope.launch { PlaybackSettingsManager.setForwardInterval(context, it) } },
                onUpdateSmartRewind = { scope.launch { PlaybackSettingsManager.setSmartRewind(context, it) } },
                onUpdateSmartRewindLimit = { scope.launch { PlaybackSettingsManager.setSmartRewindLimit(context, it) } },
                onUpdateAutoSleep = { scope.launch { PlaybackSettingsManager.setAutoSleepTimer(context, it) } },
                onToggleVolumeBoost = { scope.launch { PlaybackSettingsManager.setVolumeBoost(context, !volumeBoost) } },
                onUpdateQuickAction1 = { scope.launch { PlaybackSettingsManager.setQuickAction1(context, it) } },
                onUpdateQuickAction2 = { scope.launch { PlaybackSettingsManager.setQuickAction2(context, it) } },
                onUpdateQuickAction3 = { scope.launch { PlaybackSettingsManager.setQuickAction3(context, it) } },
                onUpdateGlobalSpeed = { scope.launch { PlaybackSettingsManager.setGlobalSpeedControl(context, it) } },
                onUpdateProgressBarSeeking = { scope.launch { PlaybackSettingsManager.setProgressBarSeeking(context, it) } },
                onUpdateListButtonOpens = { scope.launch { PlaybackSettingsManager.setListButtonOpens(context, it) } },
                onUpdateUseRemainingTime = { scope.launch { PlaybackSettingsManager.setUseRemainingTime(context, it) } },
                onUpdateUseChapterContext = { scope.launch { PlaybackSettingsManager.setUseChapterContext(context, it) } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoplaySettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val autoplayLibrary by remember { PlaybackSettingsManager.getAutoplayLibrary(context) }.collectAsState(initial = false)
    val autoplayRestartFinished by remember { PlaybackSettingsManager.getAutoplayRestartFinished(context) }.collectAsState(initial = false)

    BookPlayerTabScaffold(
        title = stringResource(R.string.settings_autoplay_label),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
                .padding(
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp + LocalMiniPlayerInset.current
                )
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            ) {
                Column {
                    SettingsRowToggle(
                        label = stringResource(R.string.settings_autoplay_library),
                        checked = autoplayLibrary,
                        onCheckedChange = { scope.launch { PlaybackSettingsManager.setAutoplayLibrary(context, it) } }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(
                        label = stringResource(R.string.settings_autoplay_restart_finished),
                        checked = autoplayRestartFinished,
                        onCheckedChange = { scope.launch { PlaybackSettingsManager.setAutoplayRestartFinished(context, it) } }
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_autoplay_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutolockSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val preventAutolock by remember { PlaybackSettingsManager.getPreventAutolock(context) }.collectAsState(initial = false)
    val preventAutolockOnlyOnPower by remember { PlaybackSettingsManager.getPreventAutolockOnlyOnPower(context) }.collectAsState(initial = false)

    BookPlayerTabScaffold(
        title = stringResource(R.string.settings_autolock_label),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp)
                .padding(
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp + LocalMiniPlayerInset.current
                )
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            ) {
                Column {
                    SettingsRowToggle(
                        label = stringResource(R.string.settings_disable_autolock),
                        checked = preventAutolock,
                        onCheckedChange = { scope.launch { PlaybackSettingsManager.setPreventAutolock(context, it) } }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(
                        label = stringResource(R.string.settings_autolock_only_on_power),
                        checked = preventAutolockOnlyOnPower,
                        enabled = preventAutolock,
                        onCheckedChange = { scope.launch { PlaybackSettingsManager.setPreventAutolockOnlyOnPower(context, it) } }
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_autolock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )
        }
    }
}
