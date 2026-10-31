package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.logic.HardcoverAccount
import com.tortugapower.audiobookplayer.logic.HardcoverSettingsManager
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.AuthTabWebAuthenticator
import com.tortugapower.audiobookplayer.ui.screens.settings.connection.SsoAvailability
import com.tortugapower.audiobookplayer.viewmodel.HardcoverSettingsViewModel
import com.tortugapower.audiobookplayer.viewmodel.HardcoverSettingsViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // The browser that can run the sign-in (Chrome's Auth Tab, as for media-server SSO), or null: then only API
    // keys are offered. Resolved off the composition pass (a few PackageManager binder calls).
    val authTabProvider by produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.Default) { SsoAvailability.authTabProvider(context.applicationContext) }
    }
    val webAuthenticator = remember(authTabProvider) {
        authTabProvider?.let { AuthTabWebAuthenticator(context.applicationContext, it) }
    }
    val authLauncher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        webAuthenticator?.deliver(result)
    }
    LaunchedEffect(webAuthenticator, authLauncher) {
        webAuthenticator?.launcher = authLauncher
        viewModel.webAuthenticator = webAuthenticator
    }
    val canSignIn = viewModel.canSignIn && webAuthenticator != null

    var token by remember { mutableStateOf("") }
    var autoMatch by remember { mutableStateOf(false) }
    var autoAdd by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf(1f) } // 1% to 100%

    // Load initial values from Datastore
    LaunchedEffect(Unit) {
        token = HardcoverSettingsManager.getToken(context).first()
        autoMatch = HardcoverSettingsManager.getAutoMatchBooks(context).first()
        autoAdd = HardcoverSettingsManager.getAutoAddToWantToRead(context).first()
        threshold = HardcoverSettingsManager.getReadingThreshold(context).first() * 100f
    }
    // A sign-in replaces the API key.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) token = ""
    }

    if (signInFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSignInFailure,
            title = { Text(stringResource(R.string.hardcover_sign_in_failed_title)) },
            text = { Text(stringResource(R.string.hardcover_sign_in_failed_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSignInFailure) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

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
        actions = {
            TextButton(
                onClick = {
                    scope.launch {
                        // Signed in, the key field is hidden and empty: there's no key to save.
                        if (!isSignedIn) HardcoverAccount.saveApiKey(token)
                        HardcoverSettingsManager.setAutoMatchBooks(context, autoMatch)
                        HardcoverSettingsManager.setAutoAddToWantToRead(context, autoAdd)
                        HardcoverSettingsManager.setReadingThreshold(context, threshold / 100f)
                        onBack()
                    }
                }
            ) {
                Text(
                    text = stringResource(R.string.common_save),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { innerPadding ->
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

            if (isSignedIn) {
                SignedInSection(
                    username = connection?.username,
                    needsSignInAgain = connection?.needsSignInAgain == true,
                    canSignIn = canSignIn,
                    isSigningIn = isSigningIn,
                    onSignIn = viewModel::signIn,
                )
            } else {
                if (canSignIn) {
                    SignInSection(isSigningIn = isSigningIn, onSignIn = viewModel::signIn)
                }
                ApiKeySection(token = token, onTokenChange = { token = it })
            }

            if (connection?.isConnected == true) {
                OutlinedButton(
                    onClick = {
                        token = ""
                        viewModel.unlink()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.hardcover_unlink))
                }
            }

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
                            onCheckedChange = { autoMatch = it }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                        SettingsToggleItem(
                            label = stringResource(R.string.hardcover_auto_add_want_to_read),
                            checked = autoAdd,
                            onCheckedChange = { autoAdd = it }
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
                            valueRange = 1f..100f
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.hardcover_progress_tracking_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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

/** Not connected: a prominent full-width sign-in button above the API key field. */
@Composable
private fun SignInSection(isSigningIn: Boolean, onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.hardcover_account_title))
        Button(
            onClick = onSignIn,
            enabled = !isSigningIn,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            // Both labels are always laid out, one hidden, so switching doesn't resize the button; the small
            // spinner is shorter than a line of text.
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.hardcover_sign_in_button),
                    style = MaterialTheme.typography.titleSmall,
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
                    Text(
                        text = stringResource(R.string.hardcover_signing_in),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.hardcover_sign_in_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Signed in with OAuth: who, and Sign In Again when the sign-in lacks a scope added since. */
@Composable
private fun SignedInSection(
    username: String?,
    needsSignInAgain: Boolean,
    canSignIn: Boolean,
    isSigningIn: Boolean,
    onSignIn: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.hardcover_account_title))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            if (username != null) stringResource(R.string.hardcover_connected_as, username)
                            else stringResource(R.string.hardcover_connected)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (needsSignInAgain && canSignIn) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.hardcover_sign_in_again_button),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            if (isSigningIn) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = !isSigningIn, role = Role.Button, onClick = onSignIn),
                    )
                }
            }
        }
        if (needsSignInAgain && canSignIn) {
            Text(
                text = stringResource(R.string.hardcover_sign_in_again_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A pasted personal API key (`hc_pat_…`), for accounts that don't sign in. */
@Composable
private fun ApiKeySection(token: String, onTokenChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.hardcover_api_access_section))

        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.hardcover_api_token_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true
        )

        // Hint with click link
        val apiLink = stringResource(R.string.hardcover_api_access_link)
        val fullHint = stringResource(R.string.hardcover_api_access_hint, apiLink)
        val annotatedLinkString = buildAnnotatedString {
            val linkStartIndex = fullHint.indexOf(apiLink)
            if (linkStartIndex != -1) {
                append(fullHint.substring(0, linkStartIndex))
                val link = androidx.compose.ui.text.LinkAnnotation.Url(
                    url = apiLink,
                    styles = androidx.compose.ui.text.TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                )
                withLink(link) {
                    append(apiLink)
                }
                append(fullHint.substring(linkStartIndex + apiLink.length))
            } else {
                append(fullHint)
            }
        }
        Text(
            text = annotatedLinkString,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}
