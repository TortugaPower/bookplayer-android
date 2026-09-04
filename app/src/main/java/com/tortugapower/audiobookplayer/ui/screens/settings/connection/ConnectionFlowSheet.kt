package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowEvent
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowMode
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowStep
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowViewModel
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowViewModelFactory

/**
 * The add-server / re-auth flow: one pushed screen per decision, mirroring iOS's
 * `IntegrationConnectionFlowView`. Address (root) → method chooser (only when the server offers an
 * alternative to the password) → password form. Owns its own nav host inside a full-height sheet;
 * the routing decision (what Connect lands on) lives on the view model, which also cancels any
 * in-flight request when the sheet goes away.
 *
 * On success the sheet hides itself *first* and only then reports [onSignedIn], so the host can
 * present the new library without presenting-while-dismissing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFlowSheet(
    type: ExternalServiceType,
    mode: ConnectionFlowMode,
    externalServerRepository: ExternalServerRepository,
    onDismiss: () -> Unit,
    onSignedIn: (ExternalServerEntity) -> Unit,
) {
    val context = LocalContext.current
    // The browser that can run the SSO leg (Auth Tab), or null — a hard requirement the routing consumes.
    val ssoProvider = remember { SsoAvailability.authTabProvider(context) }
    val reauthId = (mode as? ConnectionFlowMode.Reauth)?.server?.id
    val viewModel: ConnectionFlowViewModel = viewModel(
        key = "ConnectionFlow-$type-${reauthId ?: "add"}",
        factory = ConnectionFlowViewModelFactory(type, mode, externalServerRepository, ssoAvailableOnDevice = { ssoProvider != null })
    )
    val state by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboard = LocalSoftwareKeyboardController.current

    // The Auth Tab returns through the activity-result API, which only a composable (or Activity) can
    // register for — so the authenticator lives here and is handed to the view model.
    val webAuthenticator = remember(ssoProvider) { ssoProvider?.let { AuthTabWebAuthenticator(context.applicationContext, it) } }
    val authLauncher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        webAuthenticator?.deliver(result)
    }
    LaunchedEffect(webAuthenticator, authLauncher) {
        webAuthenticator?.launcher = authLauncher
        viewModel.attachWebAuthenticator(webAuthenticator)
    }

    fun dismiss() {
        // Leaving the flow: stop anything in flight and forget the form, so the next presentation
        // starts clean (the view model outlives the sheet).
        viewModel.reset()
        onDismiss()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConnectionFlowEvent.NavigateTo -> {
                    // A keyboard riding through the push leaves the bottom button behind it after a pop.
                    keyboard?.hide()
                    navController.navigate(event.step.route)
                }
                is ConnectionFlowEvent.SignedIn -> {
                    keyboard?.hide()
                    sheetState.hide()
                    // Reset after the hide animation, so the fields don't blank under it.
                    viewModel.reset()
                    onSignedIn(event.server)
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        NavHost(
            navController = navController,
            startDestination = "address",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
            exitTransition = { fadeOut(tween(300)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) },
        ) {
            composable("address") {
                AddressScreen(
                    state = state,
                    onSchemeChanged = viewModel::onSchemeChanged,
                    onHostChanged = viewModel::onHostChanged,
                    onPortChanged = viewModel::onPortChanged,
                    onHeaderAdded = viewModel::onHeaderAdded,
                    onHeaderChanged = viewModel::onHeaderChanged,
                    onHeaderRemoved = viewModel::onHeaderRemoved,
                    onConnect = { keyboard?.hide(); viewModel.connect() },
                    onCancel = ::dismiss,
                )
            }
            composable(ConnectionFlowStep.METHOD.route) {
                MethodScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onStartAlternative = viewModel::startAlternativeSignIn,
                    onUsePassword = viewModel::goToPassword,
                    onShowHeaders = viewModel::goToHeaders,
                )
            }
            composable(ConnectionFlowStep.PASSWORD.route) {
                PasswordScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onUsernameChanged = viewModel::onUsernameChanged,
                    onPasswordChanged = viewModel::onPasswordChanged,
                    onSignIn = { keyboard?.hide(); viewModel.signIn() },
                )
            }
            composable(ConnectionFlowStep.HEADERS.route) {
                HeadersDetailScreen(
                    headers = state.headers,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Quick Connect presents modally from the method screen. Bound to the view model's status: a
        // successful flow nils it and the sheet goes away; a failure keeps it up until the user taps OK.
        state.quickConnectStatus?.let { status ->
            QuickConnectSheet(
                status = status,
                serverUrl = state.pending?.url ?: state.url.orEmpty(),
                onCancel = viewModel::cancelQuickConnect,
            )
        }

        // Errors surface as a native alert, like every other sheet in the app; the user stays on
        // the screen that produced them (a failed Connect keeps the address screen, and its
        // scheme control, in front of them).
        AuthErrorDialog(message = state.error?.asString(), onDismiss = viewModel::clearError)
    }
}

// MARK: - Shared chrome

/** What the leading control of a flow screen does. */
enum class FlowNavigation { CANCEL, CLOSE, BACK }

/**
 * The header every flow screen shares: a leading control (worded Cancel for Add Server, an X for
 * re-auth, a back arrow on pushed screens) and a centered title. Same shape as the auth sheet's header.
 */
@Composable
fun FlowHeader(title: String, navigation: FlowNavigation, onNavigate: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (navigation) {
            FlowNavigation.CANCEL -> TextButton(onClick = onNavigate, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.primary)
            }
            FlowNavigation.CLOSE, FlowNavigation.BACK -> IconButton(
                onClick = onNavigate,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    imageVector = if (navigation == FlowNavigation.BACK) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                    contentDescription = stringResource(if (navigation == FlowNavigation.BACK) R.string.common_back else R.string.common_close),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
        )
    }
}

/** The flow's primary action — filled, full width, directly after the screen's content (never a toolbar confirmation). */
@Composable
fun FlowPrimaryButton(title: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold)
    }
}

/** An uppercase section label above a card, the style the media-server screens already use. */
@Composable
fun FlowSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

/** The rounded card the media-server screens group rows in. */
@Composable
fun FlowCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** Blocks the screen while a request is in flight — a spinner over a dim scrim, as on iOS. */
@Composable
fun FlowLoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(20.dp))
        }
    }
}
