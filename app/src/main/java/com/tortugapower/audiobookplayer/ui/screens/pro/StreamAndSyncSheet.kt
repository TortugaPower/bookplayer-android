package com.tortugapower.audiobookplayer.ui.screens.pro

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PurchaseFlowManager

/**
 * The "Stream & Sync" intro sheet for the lite tier: sells media-server streaming + progress sync
 * (no cloud file storage). Same skeleton as [BookPlayerProSheet], but continues into the lite
 * paywall ([LitePaywallSheet]) instead of the pro one.
 *
 * @param onSubscribed invoked when the user completes a lite purchase/restore from the stacked
 *   paywall, so hosts can resume the gated action (e.g. stage the stream import)
 */
@Composable
fun StreamAndSyncSheet(
    onDismiss: () -> Unit,
    onPasskeyClick: () -> Unit,
    onAuthenticated: (hasSubscription: Boolean) -> Unit,
    onSubscribed: () -> Unit = {}
) {
    SubscriptionIntroSheet(
        title = stringResource(R.string.lite_title),
        onDismiss = onDismiss,
        onPasskeyClick = onPasskeyClick,
        onAuthenticated = onAuthenticated,
        paywall = { dismissPaywall ->
            LitePaywallSheet(onDismiss = dismissPaywall, onSubscribed = onSubscribed)
        }
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Features
        ProFeatureRow(
            icon = Icons.Default.Dns,
            title = stringResource(R.string.lite_feature_server_title),
            description = stringResource(R.string.lite_feature_server_desc)
        )

        Spacer(modifier = Modifier.height(32.dp))

        ProFeatureRow(
            icon = Icons.Default.GraphicEq,
            title = stringResource(R.string.lite_feature_stream_title),
            description = stringResource(R.string.lite_feature_stream_desc)
        )

        Spacer(modifier = Modifier.height(32.dp))

        ProFeatureRow(
            icon = Icons.Default.Sync,
            title = stringResource(R.string.lite_feature_sync_title),
            description = stringResource(R.string.lite_feature_sync_desc)
        )

        // No disclaimer section here (unlike the Pro sheet): the account note doesn't apply — lite
        // IS the multi-device product — and pricing context lives on the paywall step.
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * [PaywallSheet] configured to sell the lite tier (media-server streaming + progress sync).
 */
@Composable
fun LitePaywallSheet(
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit = {}
) {
    PaywallSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.lite_title),
        monthlyOfferingId = PurchaseFlowManager.LITE_MONTHLY_OFFERING_ID,
        yearlyOfferingId = PurchaseFlowManager.LITE_YEARLY_OFFERING_ID,
        // Match any "lite" offering if the ids drift, but never fall back to the `current`
        // (pro) offering — this sheet must not sell the wrong tier.
        offeringKeyword = "lite",
        fallbackToCurrentOffering = false,
        welcomeTitle = stringResource(R.string.lite_welcome_title),
        welcomeDescription = stringResource(R.string.lite_welcome_description),
        onSubscribed = onSubscribed
    )
}
