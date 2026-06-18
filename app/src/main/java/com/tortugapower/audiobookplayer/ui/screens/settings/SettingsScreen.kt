@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.BuildConfig
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.logic.buildDebugInformation
import com.tortugapower.audiobookplayer.logic.buildSupportDebugInfo
import com.tortugapower.audiobookplayer.logic.buildSupportEmailIntents
import com.tortugapower.audiobookplayer.logic.writeSupportFile
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.ui.components.SettingsItem
import com.tortugapower.audiobookplayer.logic.SupportLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Settings tab: a grouped [LazyColumn] of settings sections — Appearance (theme picker, via
 * [onNavigateToThemes]) and Support (contact, debug info, project links), with a build-info footer.
 */
@Composable
fun SettingsScreen(onNavigateToThemes: () -> Unit, onNavigateToTipJar: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Account is included in the debug info (when signed in) so support can correlate a report.
    // Same lightweight repo-from-Compose pattern used by the Pro sheet.
    val accountRepository = remember { RoomAccountRepository(AppDatabase.getDatabase(context).accountDao()) }
    val account by accountRepository.getAccountFlow().collectAsState(initial = null)

    val appVersion = "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"
    var showEmailFallback by remember { mutableStateOf(false) }
    var isGeneratingDebug by remember { mutableStateOf(false) }

    // Shown when no email app can handle the compose intent — offers copy-to-clipboard (mirrors iOS).
    if (showEmailFallback) {
        val info = remember(account) { "${SupportLinks.SUPPORT_EMAIL}\n\n${buildSupportDebugInfo(account)}" }
        AlertDialog(
            onDismissRequest = { showEmailFallback = false },
            title = { Text(stringResource(R.string.settings_support_compose_unavailable_title)) },
            text = { Text(info) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("BookPlayer support", info)))
                    }
                    showEmailFallback = false
                }) { Text(stringResource(R.string.settings_support_copy_clipboard)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmailFallback = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    fun sendSupportEmail() {
        // The build info rides as a build-info.txt attachment; the body is just the greeting.
        // buildSupportEmailIntents writes the file and builds the per-app intents off the main thread
        // (attachment access is granted transiently via clipData — see its doc); we just launch the
        // result. One match → composer directly; several → email-only picker; none → clipboard fallback.
        scope.launch {
            val emailIntents = withContext(Dispatchers.IO) { buildSupportEmailIntents(context, account) }
            when {
                emailIntents.isEmpty() -> showEmailFallback = true
                emailIntents.size == 1 -> context.startActivity(emailIntents.first())
                else -> {
                    val chooser = Intent.createChooser(emailIntents.first(), null)
                    val rest = emailIntents.drop(1)
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, Array<Parcelable>(rest.size) { rest[it] })
                    context.startActivity(chooser)
                }
            }
        }
    }

    fun shareDebugInfo() {
        if (isGeneratingDebug) return
        isGeneratingDebug = true
        // Full dump pulls from Room + the filesystem — generate AND write off the main thread.
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    writeSupportFile(context, "bookplayer_debug_information.txt", buildDebugInformation(context))
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("bookplayer_debug_information.txt", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null))
            } finally {
                isGeneratingDebug = false
            }
        }
    }

    BookPlayerTabScaffold(title = stringResource(R.string.settings_title)) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp + LocalMiniPlayerInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            settingsSection(titleRes = R.string.settings_appearance_section) {
                SettingsItem(
                    label = stringResource(R.string.settings_theme_label),
                    value = ThemeManager.currentTheme.title,
                    onClick = onNavigateToThemes,
                )
            }

            settingsSection(titleRes = R.string.settings_support_section) {
                SettingsItem(
                    label = stringResource(R.string.settings_tip_jar_title),
                    onClick = onNavigateToTipJar,
                )
                HorizontalDivider()
                SettingsItem(
                    label = stringResource(R.string.settings_support_email_title),
                    value = SupportLinks.SUPPORT_EMAIL,
                    onClick = { sendSupportEmail() },
                )
                HorizontalDivider()
                SettingsItem(
                    label = stringResource(R.string.settings_share_debug_information),
                    value = if (isGeneratingDebug) "…" else null,
                    onClick = { shareDebugInfo() },
                )
                HorizontalDivider()
                SettingsItem(
                    label = stringResource(R.string.settings_support_project_title),
                    onClick = { uriHandler.openUri(SupportLinks.GITHUB) },
                )
                HorizontalDivider()
                SettingsItem(
                    label = stringResource(R.string.settings_support_discord_title),
                    onClick = { uriHandler.openUri(SupportLinks.DISCORD) },
                )
            }

            // Build-info footer (mirrors iOS "BookPlayer <version> - <os>").
            item {
                Text(
                    text = stringResource(R.string.settings_version_footer, appVersion, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * Adds a labelled section to a Settings-style [LazyColumn]: a small uppercase caption
 * header (Material3 grouped-list convention) followed by a [Card] that wraps the rows.
 *
 * Call from inside a [LazyColumn] body alongside other items. Each call produces two
 * [LazyListScope] items (the header and the card), spaced by the parent's
 * `verticalArrangement`. Use multiple times to build multi-section settings screens.
 *
 * Takes a string resource id (rather than a resolved `String`) because the section is
 * built outside a `@Composable` context — string resolution happens inside the item bodies.
 *
 * @param titleRes section caption shown above the card
 * @param content the rows to render inside the card — typically `SettingsItem`s and
 *                `SettingsToggleItem`s, optionally separated by [HorizontalDivider]
 */
fun LazyListScope.settingsSection(
    @androidx.annotation.StringRes titleRes: Int,
    content: @Composable () -> Unit,
) {
    item {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}
