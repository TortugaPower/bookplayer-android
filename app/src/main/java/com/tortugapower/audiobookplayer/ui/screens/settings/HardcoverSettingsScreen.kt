package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager
import com.tortugapower.audiobookplayer.viewmodel.HardcoverSettingsViewModel
import com.tortugapower.audiobookplayer.viewmodel.HardcoverSettingsViewModelFactory
import com.tortugapower.audiobookplayer.ui.components.BookPlayerSlider
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.SettingsToggleItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardcoverSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HardcoverSettingsViewModel = viewModel(
        factory = HardcoverSettingsViewModelFactory(context.applicationContext as Application)
    )
    val connection by viewModel.connection.collectAsState()
    val isSigningIn by viewModel.isSigningIn.collectAsState()
    val signInFailed by viewModel.signInFailed.collectAsState()
    val isSignedIn = connection?.isSignedInWithOAuth == true

    // The Auth Tab's result goes to the ViewModel, which holds the sign-in waiting on it: after the Activity is
    // recreated mid sign-in, this new launcher receives the result and hands it to that same sign-in.
    val authLauncher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        viewModel.deliverAuthResult(result)
    }
    DisposableEffect(authLauncher) {
        viewModel.attachAuthLauncher(authLauncher)
        onDispose { viewModel.detachAuthLauncher(authLauncher) }
    }
    // Null until the browser lookup is done: neither the button nor the reason there's none shows before.
    val canSignIn by viewModel.canSignIn.collectAsState()
    // A regular-browser sign-in can't tell when the user gives up: coming back here without its return ends the wait.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onScreenPaused() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }

    var autoMatch by remember { mutableStateOf(false) }
    var autoAdd by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf(1f) } // 1% to 100%

    // Load initial values from Datastore
    LaunchedEffect(Unit) {
        autoMatch = HardcoverSettingsManager.getAutoMatchBooks(context).first()
        autoAdd = HardcoverSettingsManager.getAutoAddToWantToRead(context).first()
        threshold = HardcoverSettingsManager.getReadingThreshold(context).first() * 100f
    }

    if (signInFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSignInFailure,
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(stringResource(R.string.hardcover_sign_in_failed_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSignInFailure) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    // The settings apply as they change, like the rest of Settings (iOS): there's nothing to save.
    BookPlayerTabScaffold(
        title = stringResource(R.string.hardcover_settings_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
    ) { innerPadding ->
        // Signing in comes first (iOS): until the user signs in, the page offers only that. An API key saved by an
        // earlier version keeps syncing, but isn't shown here; signing in replaces it.
        if (connection == null) {
            // Still reading the stored sign-in (a Keystore decrypt): neither page, so a signed-in user never sees the
            // sign-in page flash on the way in.
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding))
        } else if (!isSignedIn) {
            SignedOutContent(
                canSignIn = canSignIn,
                isSigningIn = isSigningIn,
                onSignIn = viewModel::signIn,
                modifier = Modifier
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                SignedInSection(username = connection?.username)

                // Automation Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.hardcover_automation_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
                            SettingsToggleItem(
                                label = stringResource(R.string.hardcover_auto_match_books),
                                checked = autoMatch,
                                onCheckedChange = {
                                    autoMatch = it
                                    scope.launch { HardcoverSettingsManager.setAutoMatchBooks(context, it) }
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                            SettingsToggleItem(
                                label = stringResource(R.string.hardcover_auto_add_want_to_read),
                                checked = autoAdd,
                                onCheckedChange = {
                                    autoAdd = it
                                    scope.launch { HardcoverSettingsManager.setAutoAddToWantToRead(context, it) }
                                }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.hardcover_automation_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Progress Tracking Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.hardcover_progress_tracking_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.hardcover_reading_threshold),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.hardcover_reading_threshold_percent, threshold.toInt()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            BookPlayerSlider(
                                value = threshold,
                                onValueChange = { threshold = it },
                                valueRange = 1f..100f,
                                // Stored once the drag ends, not on every step of it
                                onValueChangeFinished = {
                                    val value = threshold / 100f
                                    scope.launch { HardcoverSettingsManager.setReadingThreshold(context, value) }
                                },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.hardcover_progress_tracking_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // The page stays, showing Sign In, so another account can be signed in right away. The shared
                // log-out label, as on iOS.
                LogOutButton(onClick = viewModel::unlink)

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * Not signed in: a sign-in screen, centered. What connecting does, then the sign-in button, or why there's none (no
 * browser on the device). Scrolls when it doesn't fit (landscape, large text).
 */
@Composable
private fun SignedOutContent(
    canSignIn: Boolean?,
    isSigningIn: Boolean,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoStories,
                    contentDescription = null, // decorative: the title says what this is
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.hardcover_sign_in_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.hardcover_sign_in_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(40.dp))

            when (canSignIn) {
                true -> SignInButton(isSigningIn = isSigningIn, onSignIn = onSignIn)
                false -> Text(
                    text = stringResource(R.string.hardcover_sign_in_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Still looking for a browser that can sign in: the button's room is kept, so nothing jumps
                null -> Spacer(modifier = Modifier.height(56.dp))
            }
        }
    }
}

/** The app's primary pill button (the BookPlayer sign-in sheet's), showing progress while signing in. */
@Composable
private fun SignInButton(isSigningIn: Boolean, onSignIn: () -> Unit) {
    Button(
        onClick = onSignIn,
        enabled = !isSigningIn,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(28.dp),
        // Signing in keeps the button's look, as iOS keeps its tint: the default disabled grey reads as broken
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        // Both labels are always laid out, one hidden, so switching doesn't resize the button; the small
        // spinner is shorter than a line of text.
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.profile_sign_in),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(if (isSigningIn) 0f else 1f)
                    .then(if (isSigningIn) Modifier.clearAndSetSemantics {} else Modifier),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .alpha(if (isSigningIn) 1f else 0f)
                    .then(if (isSigningIn) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                Text(text = stringResource(R.string.hardcover_signing_in), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Log out, styled like the Account screen's: a full-width 56 dp row with the icon and the label in red. */
@Composable
private fun LogOutButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.common_logout), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Signed in: who (iOS). Like iOS, there's no Sign In Again: a sign-in lacking a scope added since skips what needs it,
 * and a prompt comes back with the first new scope.
 */
@Composable
private fun SignedInSection(username: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.profile_account))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            ListItem(
                headlineContent = {
                    // The plain username (iOS); before `me` has been read (offline at sign-in), just that it's connected
                    Text(username ?: stringResource(R.string.hardcover_connected))
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}
