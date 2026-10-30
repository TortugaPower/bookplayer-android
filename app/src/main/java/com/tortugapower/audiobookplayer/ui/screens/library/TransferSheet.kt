package com.tortugapower.audiobookplayer.ui.screens.library

import android.app.Application
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.tortugapower.audiobookplayer.viewmodel.TransferSession
import com.tortugapower.audiobookplayer.viewmodel.TransferViewModel
import com.tortugapower.audiobookplayer.viewmodel.TransferViewModelFactory
import kotlinx.coroutines.delay

/**
 * The Transfer from Computer sheet (iOS `TransferView`): the import sheet ([ImportConfirmationSheet]), showing
 * the address to open until the first file arrives, then the files like any import. Done imports them into
 * [targetFolderPath], Close throws them away.
 *
 * [sessionKey] names this opening's session, which outlives a configuration change; a new key is a new
 * session.
 */
@Composable
fun TransferSheet(sessionKey: String, targetFolderPath: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    val transferViewModel: TransferViewModel = viewModel(
        key = "transfer",
        factory = remember { TransferViewModelFactory(context.applicationContext as Application) }
    )
    // Each opening's page is in the app's current language and accent.
    val session = remember(transferViewModel, sessionKey) {
        transferViewModel.session(sessionKey, TransferViewModel.pageSettings(context, accentColor))
    }
    val status by session.status.collectAsStateWithLifecycle()
    val address by session.address.collectAsStateWithLifecycle()
    val rows by session.rows.collectAsStateWithLifecycle()
    val fileCount by session.fileCount.collectAsStateWithLifecycle()

    // Locking the phone mid-upload would stop the app and the upload, so the screen stays on while the sheet
    // is up. Restores what was set before rather than clearing it.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    // Ends a session Done or Close didn't (the sheet left another way). A configuration change keeps it.
    DisposableEffect(session) {
        val activity = context.findActivity()
        onDispose { if (activity?.isChangingConfigurations != true) session.discard() }
    }

    ImportConfirmationSheet(
        rows = rows.map { row ->
            ImportConfirmationRow(
                id = row.id,
                icon = if (row.isFolder) Icons.Filled.Folder else Icons.Filled.AudioFile,
                title = row.title,
                subtitle = if (row.isFolder) {
                    pluralStringResource(R.plurals.transfer_folder_files, row.fileCount, row.fileCount)
                } else {
                    null
                }
            )
        },
        fileCount = fileCount,
        onRemove = { session.remove(it.id) },
        onConfirm = {
            session.confirm(targetFolderPath)
            onClose()
        },
        onCancel = {
            session.discard()
            onClose()
        },
        // Only once files are listed (iOS): until then the sheet is just the address to open.
        description = stringResource(R.string.transfer_footer),
        emptyState = {
            when (status) {
                TransferSession.Status.STARTING -> CircularProgressIndicator()
                TransferSession.Status.RUNNING -> {
                    val url = address?.url
                    if (url != null) AddressInstructions(url) else NoAddress()
                }
            }
        }
    )
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
        val addressLabel = stringResource(R.string.transfer_server_url_accessibility_value, url)
        SelectionContainer {
            Text(
                text = url,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = addressLabel }
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
