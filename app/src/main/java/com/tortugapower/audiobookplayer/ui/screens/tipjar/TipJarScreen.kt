@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.tortugapower.audiobookplayer.ui.screens.tipjar

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.RestoreResult
import com.tortugapower.audiobookplayer.logic.TipJarManager
import com.tortugapower.audiobookplayer.logic.TipResult
import com.tortugapower.audiobookplayer.logic.TipTier
import com.tortugapower.audiobookplayer.network.GitHubClient
import com.tortugapower.audiobookplayer.network.GitHubContributor
import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.screens.pro.WelcomeToProDialog
import com.tortugapower.audiobookplayer.ui.theme.bpColors
import kotlinx.coroutines.launch

/** The two maintainers featured above the contributors grid (login → profile URL). */
private val FEATURED = listOf("GianniCarlo", "Hirobreak")

/**
 * Tip Jar — donate to support BookPlayer. A tip grants the RevenueCat `plus` entitlement (→
 * `AccountTier.PLUS`). Mirrors iOS `SettingsTipJarView`: description, three tip tiers (tap = buy),
 * contributors, a Restore action, and a confetti thank-you on success.
 */
@Composable
fun TipJarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var prices by remember { mutableStateOf<Map<TipTier, String>>(emptyMap()) }
    var purchasingTier by remember { mutableStateOf<TipTier?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var thanksTitle by remember { mutableStateOf<String?>(null) }
    var contributors by remember { mutableStateOf<List<GitHubContributor>>(emptyList()) }

    val genericError = stringResource(R.string.auth_error_generic)
    val thanksDefault = stringResource(R.string.tip_thanks_title)
    val thanksAmazing = stringResource(R.string.tip_thanks_amazing_title)
    val missingMessage = stringResource(R.string.tip_missing_title)

    LaunchedEffect(Unit) { prices = TipJarManager.fetchPrices() }
    LaunchedEffect(Unit) {
        // Sparse until the repo is public / PRs land; failure → empty grid (just the featured two).
        contributors = runCatching { GitHubClient.api.contributors() }.getOrDefault(emptyList())
    }

    AuthErrorDialog(message = error) { error = null }
    thanksTitle?.let { title ->
        WelcomeToProDialog(
            // Mirror iOS: acknowledging the thank-you returns to Settings.
            onDismiss = { thanksTitle = null; onBack() },
            title = title,
            description = stringResource(R.string.tip_thanks_description),
        )
    }

    fun tip(tier: TipTier) {
        val act = activity ?: return
        if (purchasingTier != null) return
        // Arm the guard synchronously (before any suspension) so a fast double-tap can't launch two
        // purchase flows, and the pill shows its spinner during the isFirstDonation round-trip.
        purchasingTier = tier
        scope.launch {
            try {
                val first = TipJarManager.isFirstDonation()
                when (val result = TipJarManager.purchaseTip(act, tier, first)) {
                    is TipResult.Success -> thanksTitle = if (first) thanksDefault else thanksAmazing
                    is TipResult.Cancelled -> {} // silent no-op
                    is TipResult.Error -> error = result.message ?: genericError
                }
            } finally {
                purchasingTier = null
            }
        }
    }

    fun restore() {
        if (isRestoring) return
        isRestoring = true
        scope.launch {
            try {
                when (val result = TipJarManager.restoreTips()) {
                    RestoreResult.Restored -> thanksTitle = thanksAmazing
                    RestoreResult.NothingToRestore -> error = missingMessage
                    is RestoreResult.Error -> error = result.message ?: genericError
                }
            } finally {
                isRestoring = false
            }
        }
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.settings_tip_jar_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        actions = {
            TextButton(onClick = { restore() }, enabled = !isRestoring) {
                Text(stringResource(R.string.common_restore), color = MaterialTheme.colorScheme.primary)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tip_extra_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.bpColors.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tip_support_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TipTier.entries.forEach { tier ->
                    TipCard(
                        title = stringResource(tier.titleRes),
                        price = prices[tier] ?: tier.fallbackPrice,
                        accentColor = Color(tier.accentArgb),
                        loading = purchasingTier == tier,
                        enabled = purchasingTier == null && !isRestoring,
                        onClick = { tip(tier) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
            ContributorsSection(contributors = contributors, uriHandler = uriHandler)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TipCard(
    title: String,
    price: String,
    accentColor: Color,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Fixed white card with a per-tier colored pill + white pill text, matching iOS.
        // The card itself isn't tappable — only the price pill below is.
        modifier = modifier
            .height(112.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF6B7280), // fixed gray — readable on the white card in any theme
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = accentColor)
            } else {
                // TalkBack: announce the full "Kind tip of $2.99" as a single button (the title is a
                // sibling Text on the card, so fold it into the clickable's label here).
                val a11yLabel = "${title.replace('\n', ' ')} $price"
                Surface(
                    color = accentColor,
                    shape = CircleShape,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClick = onClick)
                        .semantics {
                            contentDescription = a11yLabel
                            role = Role.Button
                        },
                ) {
                    Text(
                        text = price,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContributorsSection(contributors: List<GitHubContributor>, uriHandler: UriHandler) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        FEATURED.forEach { login ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ContributorAvatar(
                    avatarUrl = "https://github.com/$login.png",
                    description = "GitHub profile: @$login",
                    onOpen = { uriHandler.openUri("https://github.com/$login") },
                    size = 64.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "@$login",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val rest = contributors.filter { it.login !in FEATURED }
    if (rest.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rest.forEach { c ->
                ContributorAvatar(
                    avatarUrl = c.avatarUrl,
                    description = "GitHub profile: @${c.login}",
                    onOpen = { uriHandler.openUri(c.htmlUrl) },
                    size = 36.dp,
                )
            }
        }
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String,
    description: String,
    onOpen: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    AsyncImage(
        model = avatarUrl,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onOpen),
    )
}
