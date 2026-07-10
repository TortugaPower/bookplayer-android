@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.pro

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModel
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModelFactory
import kotlinx.coroutines.launch

import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog

/**
 * The "BookPlayer Pro" intro sheet: feature highlights + disclaimers, with a Continue button for
 * signed-in users or Google / passkey sign-in buttons when signed out. On successful auth it hands
 * off via [onAuthenticated] so the host can present the paywall to non-subscribers.
 *
 * @param onDismiss close the sheet
 * @param onPasskeyClick open the passkey [AuthSheet] (stacked above this sheet)
 * @param onAuthenticated invoked on success with whether the account already has a subscription
 */
@Composable
fun BookPlayerProSheet(
    onDismiss: () -> Unit,
    onPasskeyClick: () -> Unit,
    onAuthenticated: (hasSubscription: Boolean) -> Unit
) {
    SubscriptionIntroSheet(
        title = stringResource(R.string.pro_title),
        onDismiss = onDismiss,
        onPasskeyClick = onPasskeyClick,
        onAuthenticated = onAuthenticated,
        paywall = { dismissPaywall -> PaywallSheet(onDismiss = dismissPaywall) }
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Features (mirrors iOS LoginView's benefit list: cloud sync, watch app, themes)
        ProFeatureRow(
            icon = Icons.Default.CloudUpload,
            title = stringResource(R.string.pro_feature_cloud_sync_title),
            description = stringResource(R.string.pro_feature_cloud_sync_desc)
        )

        Spacer(modifier = Modifier.height(32.dp))

        ProFeatureRow(
            icon = Icons.Default.Watch,
            title = stringResource(R.string.pro_feature_watch_title),
            description = stringResource(R.string.pro_feature_watch_desc)
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
}

/**
 * Shared skeleton for subscription intro sheets ([BookPlayerProSheet], [StreamAndSyncSheet]):
 * fixed header (close + [title]), scrollable [content] (feature rows + disclaimers), and pinned
 * auth actions — a Continue button into [paywall] for signed-in users, or Google / passkey
 * sign-in buttons when signed out.
 *
 * @param paywall the paywall sheet to stack when a signed-in user continues; receives its
 *   dismiss callback
 */
@Composable
internal fun SubscriptionIntroSheet(
    title: String,
    onDismiss: () -> Unit,
    onPasskeyClick: () -> Unit,
    onAuthenticated: (hasSubscription: Boolean) -> Unit,
    paywall: @Composable (onDismiss: () -> Unit) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getDatabase(context)
    val accountRepository = RoomAccountRepository(db.accountDao())
    val viewModel: AuthViewModel = viewModel(
        key = "ProSheet",
        factory = AuthViewModelFactory(accountRepository)
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
        paywall { showPaywall = false }
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
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Scrollable content between the fixed header and the pinned bottom buttons.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    content = content
                )

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

