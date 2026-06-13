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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revenuecat.purchases.Package
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.AccountGate
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.PurchaseFlowManager
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.model.formatSyncTime
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
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

    if (showProSheet) {
        BookPlayerProSheet(
            onDismiss = { showProSheet = false },
            onPasskeyClick = {
                showProSheet = false
                showAuthSheet = true
            }
        )
    }

    if (showAuthSheet) {
        AuthSheet(onDismiss = { showAuthSheet = false })
    }

    BookPlayerTabScaffold(title = stringResource(R.string.profile_title)) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
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

@Composable
fun AccountDetailsScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsState()
    var showPaywall by remember { mutableStateOf(false) }

    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
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
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Pro Status Card
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
                        text = "BookPlayer Pro",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AccountFeatureRow(Icons.Default.CloudQueue, "Cloud sync (Beta)")
                        AccountFeatureRow(Icons.Default.Palette, "Themes & Icons")
                    }

                    if (account?.tier != AccountTier.PRO) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { showPaywall = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E67D4)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Complete your account", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Standard Items
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column {
                    AccountActionRow(Icons.Filled.Description, "Terms and Conditions")
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    AccountActionRow(Icons.Filled.Description, "Privacy Policy")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            /*
            // Passkey section
            Text(
                text = "Passkey",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PersonOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("iPhone", fontWeight = FontWeight.Bold)
                        Text("Created 10 Apr 2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.MoreHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            */

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
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Color.Red)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Log out", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AccountFeatureRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color(0xFF3482F6), modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AccountActionRow(icon: ImageVector, text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable { },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF3482F6), modifier = Modifier.size(24.dp))
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }

                Text(
                    text = "BookPlayer Pro",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                TextButton(
                    onClick = {
                        PurchaseFlowManager.restorePurchases { success, _ ->
                            if (success) onDismiss()
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text("Restore", color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Choose your plan", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            text = "${pkg.product.price.formatted} per ${pkg.packageType.name.lowercase()}",
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
                            if (success) onDismiss()
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
                    Text("Subscribe now", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "By continuing, you agree to Privacy Policy and Terms and Conditions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun BookPlayerProSheet(
    onDismiss: () -> Unit,
    onPasskeyClick: () -> Unit
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

    // Close sheet on success
    LaunchedEffect(viewModel.currentStep) {
        if (viewModel.currentStep == com.tortugapower.audiobookplayer.viewmodel.AuthStep.SUCCESS) {
            onDismiss()
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
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
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
                    .padding(padding)
                    .padding(horizontal = 20.dp),
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
                    .padding(padding)
                    .padding(horizontal = 20.dp),
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
