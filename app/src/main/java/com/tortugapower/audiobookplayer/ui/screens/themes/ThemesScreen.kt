@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.themes

import com.tortugapower.audiobookplayer.ui.screens.settings.SettingsToggleItem

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

import com.tortugapower.audiobookplayer.ui.screens.pro.ProRestoreButton
import com.tortugapower.audiobookplayer.ui.screens.pro.WelcomeToProDialog

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
