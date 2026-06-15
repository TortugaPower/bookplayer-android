@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens

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
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateToAccountDetails: () -> Unit,
    onNavigateToQueuedTasks: () -> Unit
) {
    val context = LocalContext.current

    // Use cached account state from ViewModel
    val account by viewModel.account.collectAsState()
    val pendingTasksCount by viewModel.pendingTasksCount.collectAsState()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()

    var showProSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }

    // After any successful sign-in/sign-up, close the auth flow and — for accounts without a
    // subscription — present the "Complete Your Account" paywall (mirrors iOS handleSignInResult).
    fun onAuthenticated(hasSubscription: Boolean) {
        showAuthSheet = false
        showProSheet = false
        if (!hasSubscription) showPaywall = true
    }

    if (showProSheet) {
        BookPlayerProSheet(
            onDismiss = { showProSheet = false },
            // Keep the Pro sheet presented underneath; the Auth sheet stacks on top so
            // dismissing it returns here rather than closing the whole flow.
            onPasskeyClick = { showAuthSheet = true },
            onAuthenticated = ::onAuthenticated
        )
    }

    if (showAuthSheet) {
        AuthSheet(
            // Cancel (✕ / back / swipe) → return to the Pro sheet underneath.
            onDismiss = { showAuthSheet = false },
            onAuthenticated = ::onAuthenticated
        )
    }

    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
    }

    BookPlayerTabScaffold(title = stringResource(R.string.profile_title)) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // Reserve space for the floating mini player so the bottom content isn't covered.
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Account Section
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .clickable {
                    if (account == null) showProSheet = true
                    else onNavigateToAccountDetails()
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (account != null) Icons.Default.Person else Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (account != null) account!!.email else stringResource(R.string.profile_setup_account),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (account != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = if (account?.tier == AccountTier.PRO) Color.DarkGray.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = account!!.tier.name.lowercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (account?.tier == AccountTier.PRO) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.profile_not_signed_in),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Statistics Section
        Text(
            text = stringResource(R.string.profile_listening_time_value),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.profile_total_listening_time),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Queued Tasks Button
        if (account != null && (account!!.tier == AccountTier.PRO || account!!.tier == AccountTier.LITE)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .clickable { onNavigateToQueuedTasks() }
            ) {
                Text(
                    text = "Queued sync tasks ($pendingTasksCount)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF3482F6),
                    fontWeight = FontWeight.Medium
                )
                if (lastSyncTimestamp != null) {
                    Text(
                        text = "Last sync: ${lastSyncTimestamp!!.formatSyncTime()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // BookPlayer Pro Section
        if (account?.tier != AccountTier.PRO) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.pro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showProSheet = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3482F6) // Approximate blue from screenshot
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pro_learn_more),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
    }
}

/** Canonical legal pages, hosted at bookplayer.app (single source of truth across platforms). */
private object LegalUrls {
    const val PRIVACY = "https://bookplayer.app/privacy"
    const val TERMS = "https://bookplayer.app/terms"
}

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
                            .onFailure { error = it.message ?: context.getString(R.string.passkey_remove_failed) }
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

@Composable
fun PaywallSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val offerings by PurchaseFlowManager.offerings.collectAsState()
    val isPurchasing by PurchaseFlowManager.isPurchasing.collectAsState()
    var selectedPackage by remember { mutableStateOf<Package?>(null) }
    val scope = rememberCoroutineScope()
    // Expand fully (like the other sheets) so the whole paywall — plans, Subscribe button, and
    // legal text — is visible instead of opening at the half-height partial detent.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showWelcome by remember { mutableStateOf(false) }

    // Celebration after a successful purchase or restore. OK closes the paywall.
    if (showWelcome) {
        WelcomeToProDialog(onDismiss = {
            showWelcome = false
            onDismiss()
        })
    }

    LaunchedEffect(Unit) {
        PurchaseFlowManager.fetchOfferings()
    }

    LaunchedEffect(offerings) {
        val currentOfferings = offerings ?: return@LaunchedEffect
        val monthly = currentOfferings.getOffering(PurchaseFlowManager.MONTHLY_OFFERING_ID)
        val yearly = currentOfferings.getOffering(PurchaseFlowManager.YEARLY_OFFERING_ID)
        var allPackages = (yearly?.availablePackages ?: emptyList()) + (monthly?.availablePackages ?: emptyList())
        if (allPackages.isEmpty() && currentOfferings.current != null) {
            allPackages = currentOfferings.current!!.availablePackages
        }
        if (selectedPackage == null) {
            selectedPackage = allPackages.firstOrNull()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Clear the system navigation bar so the Subscribe button isn't under it.
                .navigationBarsPadding()
                // Scroll so the full paywall (plans + Subscribe + legal text) stays reachable on
                // short screens / large font scales instead of clipping at the bottom.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }

                Text(
                    text = stringResource(R.string.pro_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                ProRestoreButton(
                    onRestored = { showWelcome = true },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { onClick, enabled ->
                    TextButton(onClick = onClick, enabled = enabled) {
                        Text(stringResource(R.string.common_restore), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.paywall_choose_plan), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))

            val currentOfferings = offerings
            val monthly = currentOfferings?.getOffering(PurchaseFlowManager.MONTHLY_OFFERING_ID)
            val yearly = currentOfferings?.getOffering(PurchaseFlowManager.YEARLY_OFFERING_ID)
            var allPackages = (yearly?.availablePackages ?: emptyList()) + (monthly?.availablePackages ?: emptyList())
            if (allPackages.isEmpty() && currentOfferings?.current != null) {
                allPackages = currentOfferings.current!!.availablePackages
            }

            allPackages.forEach { pkg ->
                val isSelected = selectedPackage == pkg
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(64.dp)
                        .clickable { selectedPackage = pkg },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.paywall_price_per_period,
                                pkg.product.price.formatted,
                                pkg.packageType.name.lowercase()
                            ),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val activity = context as? Activity
                    if (activity != null && selectedPackage != null) {
                        PurchaseFlowManager.purchasePackage(activity, selectedPackage!!) { success, _ ->
                            if (success) showWelcome = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5E67D4) // Keep signature brand color or use MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = !isPurchasing && selectedPackage != null
            ) {
                if (isPurchasing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.paywall_subscribe), fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val linkColor = MaterialTheme.colorScheme.primary
            val agreePrefix = stringResource(R.string.paywall_agree_prefix)
            val agreeAnd = stringResource(R.string.paywall_agree_and)
            val privacyLabel = stringResource(R.string.legal_privacy)
            val termsLabel = stringResource(R.string.legal_terms)
            Text(
                text = buildAnnotatedString {
                    append(agreePrefix)
                    withLink(
                        LinkAnnotation.Url(
                            LegalUrls.PRIVACY,
                            TextLinkStyles(style = SpanStyle(color = linkColor))
                        )
                    ) { append(privacyLabel) }
                    append(agreeAnd)
                    withLink(
                        LinkAnnotation.Url(
                            LegalUrls.TERMS,
                            TextLinkStyles(style = SpanStyle(color = linkColor))
                        )
                    ) { append(termsLabel) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Celebratory confetti for the "Welcome to Pro" moment. Mirrors the iOS CAEmitterLayer effect:
 * the same five brand colors rain down from a line across the top of the screen.
 */
private fun welcomeConfettiParty(): Party {
    val iosColors = listOf(
        0xFFF26645.toInt(), // coral
        0xFFFFC75C.toInt(), // amber
        0xFF7BC7A3.toInt(), // green
        0xFF4DC2D9.toInt(), // cyan
        0xFF94638C.toInt(), // plum
    )
    return Party(
        angle = Angle.BOTTOM,            // rain downward
        spread = 60,
        speed = 10f,
        maxSpeed = 30f,
        damping = 0.9f,
        colors = iosColors,
        // Emit continuously (long duration) so the rain keeps going until the user taps OK and
        // the KonfettiView leaves the composition. A moderate rate keeps sustained emission smooth.
        emitter = Emitter(duration = 1, java.util.concurrent.TimeUnit.HOURS).perSecond(50),
        // Emit from a line across the top, like the iOS .line emitter shape.
        position = Position.Relative(0.0, 0.0).between(Position.Relative(1.0, 0.0))
    )
}

/**
 * Reusable "Welcome to BookPlayer Pro!" celebration — a full-screen [Dialog] (its own top-level
 * window, so the confetti sits above any sheet) with continuous confetti behind a centered card.
 * [onDismiss] is called when OK is tapped (callers decide what that means — e.g. close a paywall
 * sheet, or just hide the dialog). Use anywhere a successful purchase/restore should celebrate.
 */
@Composable
fun WelcomeToProDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KonfettiView(
                // Decorative — kept out of the TalkBack tree.
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
                parties = listOf(welcomeConfettiParty())
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(40.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.pro_welcome_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pro_welcome_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            }
        }
    }
}

/**
 * Reusable Restore-purchases control. Owns the restore call, in-flight state, and the
 * "nothing to restore" / error alert; invokes [onRestored] on success (callers typically show
 * [WelcomeToProDialog]). [content] supplies the button visual — it receives the click handler and
 * an `enabled` flag (false while restoring). Use in the paywall, the Themes screen top bar, etc.
 */
@Composable
fun ProRestoreButton(
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (onClick: () -> Unit, enabled: Boolean) -> Unit
) {
    val context = LocalContext.current
    var isRestoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthErrorDialog(message = error) { error = null }

    Box(modifier) {
        content(
            {
                isRestoring = true
                PurchaseFlowManager.restorePurchases { success, err ->
                    isRestoring = false
                    if (success) {
                        onRestored()
                    } else {
                        error = err ?: context.getString(R.string.restore_none_found)
                    }
                }
            },
            !isRestoring
        )
    }
}

@Composable
fun BookPlayerProSheet(
    onDismiss: () -> Unit,
    onPasskeyClick: () -> Unit,
    onAuthenticated: (hasSubscription: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getDatabase(context)
    val accountRepository = RoomAccountRepository(db.accountDao())
    val syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
    val viewModel: AuthViewModel = viewModel(
        key = "ProSheet",
        factory = AuthViewModelFactory(accountRepository, syncTaskRepository)
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Reset when shown
    LaunchedEffect(Unit) {
        viewModel.reset()
    }

    // On success, hand off to the host so it can present the paywall to non-subscribers
    // (mirrors iOS handleSignInResult).
    LaunchedEffect(viewModel.currentStep) {
        if (viewModel.currentStep == com.tortugapower.audiobookplayer.viewmodel.AuthStep.SUCCESS) {
            onAuthenticated(viewModel.authResultHasSubscription)
        }
    }

    var showPaywall by remember { mutableStateOf(false) }

    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                // Header. No top spacer/padding here — the sheet's drag handle already
                // contributes its standard ~22dp gap above this row.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                    }

                    Text(
                        text = stringResource(R.string.pro_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Scrollable content between the fixed header and the pinned bottom buttons.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(32.dp))

                    // Features
                    ProFeatureRow(
                        icon = Icons.Default.CloudUpload,
                        title = stringResource(R.string.pro_feature_cloud_sync_title),
                        description = stringResource(R.string.pro_feature_cloud_sync_desc)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    ProFeatureRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.pro_feature_themes_title),
                        description = stringResource(R.string.pro_feature_themes_desc)
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    // Disclaimer Section
                    Text(
                        text = stringResource(R.string.pro_disclaimer_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DisclaimerItem(text = stringResource(R.string.pro_disclaimer_account))
                    DisclaimerItem(text = stringResource(R.string.pro_disclaimer_subscription))

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // --- Pinned bottom: auth buttons (errors surface via AuthErrorDialog) ---
                // Collect account state to determine if user is logged in
                val dbAccount by accountRepository.getAccountFlow().collectAsState(initial = null)

                if (dbAccount != null) {
                    // Logged in: Single Continue button
                    Button(
                        onClick = { showPaywall = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.common_continue),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // Not logged in: Login Buttons.
                    // Google-branded sign-in button per Google's branding guidelines: neutral
                    // surface, full-color "G" logo (never tinted), outline. Theme-aware so it
                    // reads correctly in light and dark.
                    OutlinedButton(
                        onClick = { scope.launch { viewModel.signInWithGoogle(context) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(12.dp),
                        enabled = viewModel.currentStep != com.tortugapower.audiobookplayer.viewmodel.AuthStep.LOADING
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_google_logo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.auth_sign_in_with_google),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onPasskeyClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.currentStep != com.tortugapower.audiobookplayer.viewmodel.AuthStep.LOADING
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.auth_continue_with_passkey),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Global Loader
            if (viewModel.currentStep == com.tortugapower.audiobookplayer.viewmodel.AuthStep.LOADING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Errors surface as a native alert dialog.
            AuthErrorDialog(message = viewModel.errorMessage) { viewModel.errorMessage = null }
        }
    }
}

@Composable
fun ProFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DisclaimerItem(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "- ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsScreen(onNavigateToThemes: () -> Unit) {
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

@Composable
fun ThemesScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showWelcome by remember { mutableStateOf(false) }

    // Restoring a purchase from here celebrates but stays on the Themes screen.
    if (showWelcome) {
        WelcomeToProDialog(onDismiss = { showWelcome = false })
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.themes_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
        },
        actions = {
            ProRestoreButton(onRestored = { showWelcome = true }) { onClick, enabled ->
                TextButton(onClick = onClick, enabled = enabled) {
                    Text(stringResource(R.string.common_restore), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    SettingsToggleItem(
                        label = stringResource(R.string.themes_use_system_mode),
                        checked = ThemeManager.useSystemMode,
                        onCheckedChange = { ThemeManager.setUseSystemMode(context, it) },
                    )
                    HorizontalDivider()
                    SettingsToggleItem(
                        label = stringResource(R.string.themes_always_use_dark),
                        checked = ThemeManager.useDarkVariant,
                        enabled = !ThemeManager.useSystemMode,
                        onCheckedChange = { ThemeManager.setUseDarkVariant(context, it) },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.themes_section_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    ThemeManager.allThemes.forEachIndexed { index, theme ->
                        ThemeItem(
                            theme = theme,
                            isSelected = ThemeManager.currentTheme.title == theme.title,
                            onClick = { ThemeManager.setTheme(context, theme) },
                        )
                        if (index < ThemeManager.allThemes.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItem(label: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SettingsToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            Text(label, color = color)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    )
}

@Composable
fun ThemeItem(theme: BookPlayerThemeSpec, isSelected: Boolean, onClick: () -> Unit) {
    val isLockedForUser = theme.locked && !AccountGate.isPro()
    ListItem(
        headlineContent = {
            Text(
                text = theme.title,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        leadingContent = {
            ThemeShowcase(
                theme = theme,
                modifier = if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else Modifier,
            )
        },
        trailingContent = if (isSelected || isLockedForUser) {
            {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.common_pro_locked), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !isLockedForUser, onClick = onClick),
    )
}

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

@Composable
private fun ThemeShowcase(theme: BookPlayerThemeSpec, modifier: Modifier = Modifier) {
    val s = theme.showcaseLightColors
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(s.background))
                Box(Modifier.weight(1f).fillMaxHeight().background(s.accent))
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(s.primary))
                Box(Modifier.weight(1f).fillMaxHeight().background(s.secondary))
            }
        }
    }
}
