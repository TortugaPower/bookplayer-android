@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.synctasks

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.revenuecat.purchases.Package
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.AccountGate
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.PurchaseFlowManager
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.model.formatSyncTime
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModel
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModelFactory
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

@Composable
fun QueuedTasksScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onNavigateToQueue: (String) -> Unit
) {
    val tasks by viewModel.syncTasks.collectAsState()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()

    val queues = tasks.groupBy { it.queueKey }

    BookPlayerTabScaffold(
        title = "Queued Tasks",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = {
            if (tasks.isNotEmpty()) {
                IconButton(onClick = { viewModel.deleteAllTasks() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete all tasks")
                }
            }
        }
    ) { padding ->
        if (queues.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = "No pending tasks",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + LocalMiniPlayerInset.current
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                queues.forEach { (queueKey, queueTasks) ->
                    val pendingInQueue = queueTasks.count { it.status != SyncTaskStatus.COMPLETED }
                    val runningTask = queueTasks.find { it.status == SyncTaskStatus.RUNNING }

                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToQueue(queueKey) },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (queueKey == "sync") "Sync Tasks ($pendingInQueue)" else "File Tasks ($pendingInQueue)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (runningTask != null) "Processing..." else "Last sync: ${lastSyncTimestamp?.formatSyncTime() ?: "Never"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskDetailScreen(
    viewModel: ProfileViewModel,
    queueKey: String,
    onBack: () -> Unit
) {
    val tasks by viewModel.syncTasks.collectAsState()
    val progressMap by viewModel.taskProgress.collectAsState()
    val filteredTasks = tasks.filter { it.queueKey == queueKey }

    val pendingCount = filteredTasks.count { it.status != SyncTaskStatus.COMPLETED }
    val title = if (queueKey == "sync") "Sync Tasks ($pendingCount)" else "File Tasks ($pendingCount)"

    BookPlayerTabScaffold(
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
    ) { padding ->
        if (filteredTasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = "No tasks in this queue",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + LocalMiniPlayerInset.current
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(filteredTasks.size)   { index ->
                   val task = filteredTasks[index]
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
                       SyncTaskFactory.JOB_DOWNLOAD_FILE -> Icons.Default.Download to stringResource(R.string.sync_task_download_audio)
                       SyncTaskFactory.JOB_SYNC_IDENTIFIERS -> Icons.Default.Person to stringResource(R.string.sync_task_sync_identifiers)
                       SyncTaskFactory.JOB_MATCH_UUIDS -> Icons.Default.SyncAlt to stringResource(R.string.sync_task_match_library_ids)
                       else -> Icons.Default.Sync to stringResource(R.string.sync_task_generic)
                   }

                   val subject = payload["title"] as? String ?: payload["relativePath"] as? String ?: ""
                   val title = if (subject.isNotEmpty()) "$label: $subject" else label

                   Surface(
                       modifier = Modifier.fillMaxWidth(),
                       shape = RoundedCornerShape(16.dp),
                       color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                   ) {
                       Row(
                           modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
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
                               if (task.errorMessage != null) {
                                   Text(
                                       text = task.errorMessage,
                                       style = MaterialTheme.typography.labelSmall,
                                       color = Color.Red,
                                       maxLines = 1,
                                       overflow = TextOverflow.Ellipsis
                                   )
                               }
                           }

                           if (task.status == SyncTaskStatus.RUNNING) {
                               Spacer(modifier = Modifier.width(16.dp))
                               val progress = progressMap[task.id]
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

            }
        }
    }
}

