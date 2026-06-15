@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

import com.tortugapower.audiobookplayer.ui.screens.pro.PaywallSheet
import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog
import com.tortugapower.audiobookplayer.ui.components.LegalUrls

/**
 * Account details for a signed-in user: Pro status (or a Manage-Subscription row for subscribers),
 * legal links, passkey management ([AccountPasskeySection]), logout, and the destructive
 * delete-account flow. Scrollable, reserving space for the floating mini player.
 *
 * @param onBack pop back to the Profile tab (also invoked after a successful account deletion)
 */
@Composable
fun AccountDetailsScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showPaywall by remember { mutableStateOf(false) }

    // Delete-account flow state.
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteResultMessage by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
    }

    // Destructive confirmation before deleting the account.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = { Text(stringResource(R.string.account_delete_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        isDeleting = true
                        scope.launch {
                            val result = viewModel.deleteAccount()
                            isDeleting = false
                            result
                                .onSuccess { deleteResultMessage = it ?: context.getString(R.string.account_deleted_message) }
                                .onFailure { deleteError = context.getString(R.string.account_delete_failed) }
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Success — account deleted; acknowledge and leave the screen.
    if (deleteResultMessage != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.account_deleted_title)) },
            text = { Text(deleteResultMessage!!) },
            confirmButton = {
                TextButton(onClick = {
                    deleteResultMessage = null
                    onBack()
                }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    // Failure surfaced as an alert.
    AuthErrorDialog(message = deleteError) { deleteError = null }

    // In-progress indicator while the delete request is running.
    if (isDeleting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.account_deleting))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = account?.email ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(end = 48.dp)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        // Scrollable so content never clips on shorter screens. The verticalScroll viewport is
        // full-height (top inset applied inside) so content scrolls behind the floating mini
        // player; the trailing spacer reserves LocalMiniPlayerInset so the last row clears it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Pro Status Card — only shown to users who haven't completed a Pro subscription.
            if (account?.tier != AccountTier.PRO) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.pro_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AccountFeatureRow(Icons.Default.CloudQueue, stringResource(R.string.pro_feature_cloud_sync_title))
                            AccountFeatureRow(Icons.Default.Palette, stringResource(R.string.pro_feature_themes_title))
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { showPaywall = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.account_complete), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            } else {
                // Pro subscribers: manage the subscription via the Play Store. The Android RC
                // SDK has no showManageSubscriptions(); we open the customer's management URL
                // (falling back to the generic Play subscriptions screen if it isn't available).
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    AccountActionRow(Icons.Default.Settings, stringResource(R.string.account_manage_subscription)) {
                        val url = SubscriptionManager.managementUrl?.toString()
                            ?: "https://play.google.com/store/account/subscriptions"
                        uriHandler.openUri(url)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // Standard Items
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column {
                    AccountActionRow(Icons.Filled.Description, stringResource(R.string.legal_terms)) {
                        uriHandler.openUri(LegalUrls.TERMS)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    AccountActionRow(Icons.Filled.Description, stringResource(R.string.legal_privacy)) {
                        uriHandler.openUri(LegalUrls.PRIVACY)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Passkey section (list / add / remove). Requires a signed-in account.
            account?.email?.let { email ->
                AccountPasskeySection(accountEmail = email)
            }

            // Logout Button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        viewModel.logout()
                        onBack()
                    },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.account_log_out), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Delete Account (destructive — confirmed via dialog).
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !isDeleting) { showDeleteConfirm = true },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.account_delete_title), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }

            // Reserve space for the floating mini player so the last row clears it.
            Spacer(modifier = Modifier.height(24.dp + LocalMiniPlayerInset.current))
        }
    }
}

@Composable
fun AccountFeatureRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Passkey management for the signed-in account. Lists the existing passkey (one per account,
 * matching iOS), lets the user add one via the system credential sheet, or remove it. Loads on
 * first composition. Mirrors iOS `AccountPasskeySectionView`.
 */
@Composable
private fun AccountPasskeySection(accountEmail: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var passkey by remember { mutableStateOf<com.tortugapower.audiobookplayer.model.PasskeyInfo?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    suspend fun reload() {
        isLoading = true
        com.tortugapower.audiobookplayer.logic.PasskeyManager.listPasskeys()
            .onSuccess { passkey = it.firstOrNull(); loadFailed = false }
            // Clear the cached passkey on failure so a transient error (notably right after a
            // delete) surfaces the Retry state instead of a stale / just-removed passkey row.
            .onFailure { passkey = null; loadFailed = true }
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    AuthErrorDialog(message = error) { error = null }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.passkey_remove_title)) },
            text = { Text(stringResource(R.string.passkey_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val id = passkey?.id ?: return@TextButton
                    scope.launch {
                        com.tortugapower.audiobookplayer.logic.PasskeyManager.deletePasskey(id)
                            .onSuccess { reload() }
                            .onFailure {
                                error = when (it) {
                                    is com.tortugapower.audiobookplayer.logic.PasskeyOnlyMethodException ->
                                        context.getString(R.string.passkey_remove_only_method)
                                    else -> context.getString(R.string.passkey_remove_failed)
                                }
                            }
                    }
                }) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Text(
        text = stringResource(R.string.passkey_section_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        when {
            isLoading || isAdding -> {
                Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            passkey != null -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(passkey?.deviceName ?: stringResource(R.string.passkey_unnamed_device), fontWeight = FontWeight.Bold)
                        val created = passkey?.createdAt?.substringBefore('T')
                        if (!created.isNullOrBlank()) {
                            Text(
                                stringResource(R.string.passkey_created, created),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        }
                    }
                }
            }
            loadFailed -> {
                AccountActionRow(Icons.Default.Refresh, stringResource(R.string.common_retry)) {
                    scope.launch { reload() }
                }
            }
            else -> {
                AccountActionRow(Icons.Default.Fingerprint, stringResource(R.string.passkey_add)) {
                    scope.launch {
                        isAdding = true
                        com.tortugapower.audiobookplayer.logic.PasskeyManager.addPasskey(context, accountEmail)
                            .onSuccess { reload() }
                            .onFailure {
                                if (it !is com.tortugapower.audiobookplayer.logic.PasskeyCancelledException) {
                                    error = it.message ?: context.getString(R.string.passkey_add_failed)
                                }
                            }
                        isAdding = false
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
fun AccountActionRow(icon: ImageVector, text: String, onClick: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable { onClick() },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

