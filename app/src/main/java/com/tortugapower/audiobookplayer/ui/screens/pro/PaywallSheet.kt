@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.pro

import com.tortugapower.audiobookplayer.ui.components.LegalUrls

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.revenuecat.purchases.Package
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PurchaseFlowManager

/**
 * The subscription paywall bottom sheet: lists the available RevenueCat packages, lets the user
 * subscribe, and offers Restore (via [ProRestoreButton]). A successful purchase or restore shows
 * [WelcomeToProDialog], whose OK dismisses the whole sheet.
 *
 * @param onDismiss close the paywall
 */
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

