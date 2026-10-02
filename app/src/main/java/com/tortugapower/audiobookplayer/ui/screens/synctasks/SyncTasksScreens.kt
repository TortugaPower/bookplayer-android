@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.synctasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.QueuedTaskSection
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel

@Composable
private fun laneName(queueKey: String): String = when (queueKey.lowercase()) {
    SyncTaskFactory.QUEUE_SYNC -> stringResource(R.string.sync_queue_sync)
    SyncTaskFactory.QUEUE_FILE -> stringResource(R.string.sync_queue_file)
    SyncTaskFactory.QUEUE_PIPE -> stringResource(R.string.sync_queue_pipe)
    SyncTaskFactory.QUEUE_PREFERENCES -> stringResource(R.string.sync_queue_preferences)
    SyncTaskFactory.QUEUE_HARDCOVER -> stringResource(R.string.sync_queue_hardcover)
    "jellyfin" -> stringResource(R.string.sync_queue_jellyfin)
    "audiobookshelf" -> stringResource(R.string.sync_queue_audiobookshelf)
    else -> queueKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
}

private fun laneIcon(queueKey: String): ImageVector = when (queueKey.lowercase()) {
    SyncTaskFactory.QUEUE_SYNC -> Icons.Default.CloudSync
    SyncTaskFactory.QUEUE_FILE -> Icons.Default.SwapVert
    SyncTaskFactory.QUEUE_PIPE -> Icons.Default.CloudUpload
    SyncTaskFactory.QUEUE_PREFERENCES -> Icons.Default.Tune
    SyncTaskFactory.QUEUE_HARDCOVER -> Icons.AutoMirrored.Filled.MenuBook
    else -> Icons.Default.Dns
}

/**
 * Every queued task on one screen (iOS `QueuedTasksView`): a collapsible section per lane, the sync
 * lane first, then the rest alphabetically. A lane that can't make progress until a parked task is
 * resumed says so in red. Clearing the queue is left to signing out.
 *
 * @param onBack pop back to Profile
 */
@Composable
fun QueuedTasksScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
) {
    // Null while the queue is first read: nothing is drawn rather than an empty state that isn't true
    val sections = viewModel.queuedTaskSections.collectAsState().value
    val progressMap by viewModel.taskProgress.collectAsState()
    // Stored inverted on purpose: a lane that appears while the screen is open starts expanded
    var collapsedLanes by rememberSaveable { mutableStateOf(emptySet<String>()) }

    BookPlayerTabScaffold(
        title = stringResource(R.string.queued_tasks_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
    ) { padding ->
        if (sections == null) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else if (sections.isEmpty()) {
            EmptyQueue(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + LocalMiniPlayerInset.current
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { section ->
                    val expanded = section.queueKey !in collapsedLanes
                    item(key = "lane:${section.queueKey}") {
                        LaneHeader(
                            section = section,
                            expanded = expanded,
                            onToggle = {
                                collapsedLanes = if (expanded) collapsedLanes + section.queueKey else collapsedLanes - section.queueKey
                            },
                            // Space between lanes; the first sits under the top padding
                            modifier = if (section === sections.first()) Modifier else Modifier.padding(top = 12.dp),
                        )
                    }
                    if (expanded) {
                        // Lazily laid out row by row: a first sync can queue thousands of tasks
                        items(section.tasks, key = { it.id }) { task ->
                            QueuedTaskRow(task, progressMap[task.id])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sync_tasks_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.sync_tasks_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LaneHeader(
    section: QueuedTaskSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateText = stringResource(if (expanded) R.string.queued_tasks_lane_expanded else R.string.queued_tasks_lane_collapsed)
    val toggleLabel = stringResource(if (expanded) R.string.queued_tasks_lane_collapse else R.string.queued_tasks_lane_expand)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleLabel, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (section.isBlocked) Icons.Default.Warning else laneIcon(section.queueKey),
                contentDescription = null,
                tint = if (section.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = laneName(section.queueKey),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (section.isBlocked) {
                    Text(
                        text = stringResource(R.string.sync_paused_lane_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = section.tasks.size.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One task: its type icon, label and the item it touches, its error if any, live progress while it runs */
@Composable
private fun QueuedTaskRow(task: SyncTaskEntity, progress: Double?) {
    val payload = remember(task.payload) {
        try { com.google.gson.Gson().fromJson(task.payload, Map::class.java) } catch (e: Exception) { emptyMap<String, Any>() }
    }

    val (icon, label) = when (task.jobType) {
        SyncTaskFactory.JOB_UPLOAD_METADATA -> Icons.Default.CloudUpload to stringResource(R.string.sync_task_upload_metadata)
        SyncTaskFactory.JOB_UPDATE -> Icons.Default.Edit to stringResource(R.string.sync_task_update_progress)
        SyncTaskFactory.JOB_MOVE -> Icons.AutoMirrored.Filled.Forward to stringResource(R.string.sync_task_move_item)
        SyncTaskFactory.JOB_DELETE -> Icons.Default.Delete to stringResource(R.string.sync_task_delete_item)
        SyncTaskFactory.JOB_SET_BOOKMARK -> Icons.Default.Bookmark to stringResource(R.string.sync_task_set_bookmark)
        SyncTaskFactory.JOB_DELETE_BOOKMARK -> Icons.Default.BookmarkBorder to stringResource(R.string.sync_task_remove_bookmark)
        SyncTaskFactory.JOB_RENAME_FOLDER -> Icons.Default.Edit to stringResource(R.string.sync_task_rename_folder)
        SyncTaskFactory.JOB_UPLOAD_ARTWORK -> Icons.Default.Image to stringResource(R.string.sync_task_upload_artwork)
        SyncTaskFactory.JOB_FETCH_CONTENTS -> Icons.Default.Refresh to stringResource(R.string.sync_task_fetch_library)
        SyncTaskFactory.JOB_UPLOAD_FILE -> Icons.Default.Upload to stringResource(R.string.sync_task_upload_audio)
        SyncTaskFactory.JOB_UPLOAD_STREAM_FILE -> Icons.Default.CloudUpload to stringResource(R.string.sync_task_upload_stream_file)
        SyncTaskFactory.JOB_DOWNLOAD_FILE -> Icons.Default.Download to stringResource(R.string.sync_task_download_audio)
        SyncTaskFactory.JOB_SYNC_IDENTIFIERS -> Icons.Default.Person to stringResource(R.string.sync_task_sync_identifiers)
        SyncTaskFactory.JOB_MATCH_UUIDS -> Icons.Default.SyncAlt to stringResource(R.string.sync_task_match_library_ids)
        SyncTaskFactory.JOB_UPLOAD_EXTERNAL_RESOURCE -> Icons.Default.CloudUpload to stringResource(R.string.sync_task_upload_external_resource)
        SyncTaskFactory.JOB_DELETE_EXTERNAL_RESOURCE -> Icons.Default.Delete to stringResource(R.string.sync_task_delete_external_resource)
        SyncTaskFactory.JOB_HARDCOVER_AUTO_MATCH -> Icons.Default.Search to stringResource(R.string.sync_task_hardcover_auto_match)
        SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS -> Icons.Default.Check to stringResource(R.string.sync_task_hardcover_update_status)
        SyncTaskFactory.JOB_EXTERNAL_UPDATE -> Icons.Default.Dns to stringResource(R.string.sync_task_external_update)
        SyncTaskFactory.JOB_SET_EXTERNAL_RESOURCE_TO_DOWNLOAD -> Icons.Default.Download to stringResource(R.string.sync_task_set_external_resource_to_download)
        else -> Icons.Default.Sync to stringResource(R.string.sync_task_generic)
    }

    val subject = payload["title"] as? String ?: payload["relativePath"] as? String ?: ""
    val title = if (subject.isNotEmpty()) "$label: $subject" else label

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconTint = if (task.status == SyncTaskStatus.FAILED || task.errorMessage != null) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(32.dp)
                    .background(iconTint.copy(alpha = 0.1f), CircleShape)
                    .padding(6.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val errorMessage = task.errorMessage
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Red,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (task.status == SyncTaskStatus.RUNNING) {
                Spacer(modifier = Modifier.width(16.dp))
                if (progress != null && progress > 0.0) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.width(64.dp).height(4.dp),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
