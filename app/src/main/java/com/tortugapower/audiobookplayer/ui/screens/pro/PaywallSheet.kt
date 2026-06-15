@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.pro

import com.tortugapower.audiobookplayer.ui.components.LegalUrls

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
                    Text("Subscribe now", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val linkColor = MaterialTheme.colorScheme.primary
            Text(
                text = buildAnnotatedString {
                    append("By continuing, you agree to ")
                    withLink(
                        LinkAnnotation.Url(
                            LegalUrls.PRIVACY,
                            TextLinkStyles(style = SpanStyle(color = linkColor))
                        )
                    ) { append("Privacy Policy") }
                    append(" and ")
                    withLink(
                        LinkAnnotation.Url(
                            LegalUrls.TERMS,
                            TextLinkStyles(style = SpanStyle(color = linkColor))
                        )
                    ) { append("Terms and Conditions") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

