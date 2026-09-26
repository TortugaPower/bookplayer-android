package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.QuickConnectStatus

/**
 * The Quick Connect handoff, presented modally over the flow: a code to type into the server's web
 * UI, then a spinner while the approved secret is exchanged, or a terminal failure. A pure renderer —
 * the view model owns the poller; this only reports [onCancel] (Cancel while in progress, OK once failed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectSheet(
    status: QuickConnectStatus,
    serverUrl: String,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.media_servers_quick_connect_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(if (status is QuickConnectStatus.Failed) R.string.common_ok else R.string.common_cancel))
                }
            }

            when (status) {
                QuickConnectStatus.RetrievingCode -> ProgressBlock(stringResource(R.string.media_servers_quick_connect_retrieving_message))
                QuickConnectStatus.Authenticating -> ProgressBlock(stringResource(R.string.media_servers_quick_connect_authenticating_message))
                is QuickConnectStatus.AwaitingCode -> {
                    // The sheet swaps its whole content as the flow advances; without a live region a
                    // TalkBack user hears "Requesting a code…" and then silence.
                    Text(
                        text = stringResource(R.string.media_servers_quick_connect_awaiting_message),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    CodeTile(code = status.code)
                    Instructions(serverUrl = serverUrl)
                }
                is QuickConnectStatus.Failed -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = status.message.asString(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The user-facing code, large and monospaced so it's quick to read off the device and unambiguous to
 * retype (0/O, 1/l). Tap or long-press copies it: the authorizing session is often a browser tab on this
 * same phone, not a TV — and a tile that announces as clickable must do something on a tap. TalkBack
 * reads it character by character and exposes Copy as a custom action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeTile(code: String) {
    val clipboard = LocalClipboardManager.current
    val codeLabel = stringResource(R.string.media_servers_quick_connect_code_label)
    val copyLabel = stringResource(R.string.media_servers_quick_connect_copy_code)
    val spelled = code.map { it.toString() }.joinToString(" ")
    val copy = { clipboard.setText(AnnotatedString(code)) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = copy, onLongClick = copy)
            .semantics {
                contentDescription = "$codeLabel, $spelled"
                customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
            },
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        )
    }
}

/** Step-by-step instructions; step 1 carries the server URL as a tappable link. */
@Composable
private fun Instructions(serverUrl: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.media_servers_quick_connect_instructions_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        InstructionRow(1, openInstruction(serverUrl))
        InstructionRow(2, AnnotatedString(stringResource(R.string.media_servers_quick_connect_instruction_sign_in)))
        InstructionRow(3, AnnotatedString(stringResource(R.string.media_servers_quick_connect_instruction_open_menu)))
        InstructionRow(4, AnnotatedString(stringResource(R.string.media_servers_quick_connect_instruction_enter_code)))
    }
}

/**
 * Step 1's sentence with the server URL turned into a link. Built by annotating the *formatted*
 * sentence rather than splitting the string around the URL, so every locale keeps its own word order
 * with no translation work. Only http/https addresses become links — defence in depth against a
 * stored `javascript:`/`file:` address ever becoming tappable.
 */
@Composable
private fun openInstruction(serverUrl: String): AnnotatedString {
    val sentence = stringResource(R.string.media_servers_quick_connect_instruction_open, serverUrl)
    val start = sentence.indexOf(serverUrl)
    val isWebUrl = serverUrl.startsWith("http://", ignoreCase = true) || serverUrl.startsWith("https://", ignoreCase = true)
    if (start < 0 || !isWebUrl) return AnnotatedString(sentence)
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        append(sentence.substring(0, start))
        withLink(LinkAnnotation.Url(serverUrl, linkStyle)) { append(serverUrl) }
        append(sentence.substring(start + serverUrl.length))
    }
}

@Composable
private fun InstructionRow(number: Int, text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
