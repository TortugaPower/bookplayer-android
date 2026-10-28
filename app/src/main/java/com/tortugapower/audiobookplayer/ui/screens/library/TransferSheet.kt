package com.tortugapower.audiobookplayer.ui.screens.library

import android.app.Application
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.ui.components.findActivity
import com.tortugapower.audiobookplayer.viewmodel.TransferViewModel
import com.tortugapower.audiobookplayer.viewmodel.TransferViewModelFactory
import kotlinx.coroutines.delay

/**
 * The Transfer from Computer sheet (iOS `TransferView`): the import sheet's shell, showing the address to
 * open until the first file arrives, then the files like any import. Done imports them into
 * [targetFolderPath], Close throws them away. Like iOS, a swipe, a tap outside or Back can't close it: that
 * would throw away what a computer may still be sending.
 *
 * [sessionKey] names this session's ViewModel, which outlives a configuration change; a new key is a new
 * session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(sessionKey: String, targetFolderPath: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    val viewModel: TransferViewModel = viewModel(
        key = "transfer-$sessionKey",
        factory = remember {
            TransferViewModelFactory(
                context.applicationContext as Application,
                TransferViewModel.pageSettings(context, accentColor)
            )
        }
    )
    val status by viewModel.status.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val fileCount by viewModel.fileCount.collectAsStateWithLifecycle()

    // Locking the phone mid-upload would stop the app and the upload, so the screen stays on while the sheet
    // is up. Restores what was set before rather than clearing it.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    // Ends a session Done or Close didn't (the sheet left another way). A configuration change keeps it.
    DisposableEffect(viewModel) {
        val activity = context.findActivity()
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.discard() }
    }

    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        viewModel.discard()
                        onClose()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.confirm(targetFolderPath)
                        onClose()
                    },
                    enabled = fileCount > 0,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.common_accept),
                        tint = if (fileCount > 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.transfer_title),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.transfer_footer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when (status) {
                        TransferViewModel.Status.STARTING -> CircularProgressIndicator()
                        TransferViewModel.Status.RUNNING -> {
                            val url = address?.url
                            if (url != null) AddressInstructions(url) else NoAddress()
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.import_files_count, fileCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.id }) { row ->
                        TransferListItem(row, onRemove = { viewModel.remove(row.id) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressInstructions(url: String) {
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    // Bumped by every Copy, so a second tap within the feedback's two seconds gets its own.
    var copies by rememberSaveable { mutableIntStateOf(0) }
    var showsCopied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        delay(2_000)
        showsCopied = false
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.LaptopChromebook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.transfer_instructions),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        val addressLabel = stringResource(R.string.transfer_server_url_accessibility)
        SelectionContainer {
            Text(
                text = url,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = "$addressLabel, $url" }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(url))
                showsCopied = true
                copies++
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.KEYBOARD_TAP
                )
            }
        ) {
            Icon(
                if (showsCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (showsCopied) R.string.transfer_server_copied_button else R.string.transfer_server_copy_button
                ),
                // Spoken when it turns to "Copied": the button keeps focus, so its change isn't read otherwise.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun NoAddress() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.transfer_no_address),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TransferListItem(row: TransferViewModel.Row, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.RemoveCircle,
            contentDescription = stringResource(R.string.common_remove),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(28.dp)
                .clickable { onRemove() }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Icon(
            imageVector = if (row.isFolder) Icons.Filled.Folder else Icons.Filled.AudioFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = row.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1
            )
            if (row.isFolder) {
                Text(
                    text = pluralStringResource(R.plurals.transfer_folder_files, row.fileCount, row.fileCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}
