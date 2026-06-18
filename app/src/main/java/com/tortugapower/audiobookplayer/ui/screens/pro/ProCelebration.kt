@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.pro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PurchaseFlowManager

import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog

/**
 * Celebratory confetti for the "Welcome to Pro" moment. Mirrors the iOS CAEmitterLayer effect:
 * the same five brand colors rain down from a line across the top of the screen.
 */
private fun welcomeConfettiParty(): Party {
    val iosColors = listOf(
        0xFFF26645.toInt(), // coral
        0xFFFFC75C.toInt(), // amber
        0xFF7BC7A3.toInt(), // green
        0xFF4DC2D9.toInt(), // cyan
        0xFF94638C.toInt(), // plum
    )
    return Party(
        angle = Angle.BOTTOM,            // rain downward
        spread = 60,
        speed = 10f,
        maxSpeed = 30f,
        damping = 0.9f,
        colors = iosColors,
        // Emit continuously (long duration) so the rain keeps going until the user taps OK and
        // the KonfettiView leaves the composition. A moderate rate keeps sustained emission smooth.
        emitter = Emitter(duration = 1, java.util.concurrent.TimeUnit.HOURS).perSecond(50),
        // Emit from a line across the top, like the iOS .line emitter shape.
        position = Position.Relative(0.0, 0.0).between(Position.Relative(1.0, 0.0))
    )
}

/**
 * Reusable "Welcome to BookPlayer Pro!" celebration — a full-screen [Dialog] (its own top-level
 * window, so the confetti sits above any sheet) with continuous confetti behind a centered card.
 * [onDismiss] is called when OK is tapped (callers decide what that means — e.g. close a paywall
 * sheet, or just hide the dialog). Use anywhere a successful purchase/restore should celebrate.
 */
@Composable
fun WelcomeToProDialog(
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.pro_welcome_title),
    description: String = stringResource(R.string.pro_welcome_description),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KonfettiView(
                // Decorative — kept out of the TalkBack tree.
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
                parties = listOf(welcomeConfettiParty())
            )
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.padding(40.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.common_ok))
                    }
                }
            }
        }
    }
}

/**
 * Reusable Restore-purchases control. Owns the restore call, in-flight state, and the
 * "nothing to restore" / error alert; invokes [onRestored] on success (callers typically show
 * [WelcomeToProDialog]). [content] supplies the button visual — it receives the click handler and
 * an `enabled` flag (false while restoring). Use in the paywall, the Themes screen top bar, etc.
 */
@Composable
fun ProRestoreButton(
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (onClick: () -> Unit, enabled: Boolean) -> Unit
) {
    val context = LocalContext.current
    var isRestoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthErrorDialog(message = error) { error = null }

    Box(modifier) {
        content(
            {
                isRestoring = true
                PurchaseFlowManager.restorePurchases { success, err ->
                    isRestoring = false
                    if (success) {
                        onRestored()
                    } else {
                        error = err ?: context.getString(R.string.restore_none_found)
                    }
                }
            },
            !isRestoring
        )
    }
}

