package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.AUDIOBOOKSHELF
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType.JELLYFIN
import com.tortugapower.audiobookplayer.logic.ConnectionRouting.Decision
import com.tortugapower.audiobookplayer.logic.ConnectionRouting.Step
import com.tortugapower.audiobookplayer.network.AlternativeSignIn
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The routing decision the connection flow hangs on: which screen Connect lands on, per what the server
 * offers and what the device can do. Wrong routing is invisible in review — a server config you don't
 * have renders a screen you never see — so the whole matrix is pinned (same rows as iOS's
 * testConnectRoutesToTheRightStep, plus the Android-only Auth Tab gate).
 */
class ConnectionRoutingTest {

    private fun abs(methods: List<String>, buttonText: String? = null) = ServerCapabilities(
        supportsPassword = methods.isEmpty() || "local" in methods,
        supportsOidc = "openid" in methods,
        oidcButtonText = buttonText,
    )

    private fun decide(
        capabilities: ServerCapabilities,
        isSecure: Boolean = true,
        ssoAvailable: Boolean = true,
    ) = ConnectionRouting.decide(AUDIOBOOKSHELF, capabilities, isSecure, ssoAvailable)

    // Both methods → the chooser, SSO primary, password still offered there.
    @Test fun `both methods route to the method screen with password offered`() {
        assertEquals(
            Decision.Route(Step.METHOD, AlternativeSignIn.Oidc(null), supportsPassword = true),
            decide(abs(listOf("local", "openid"))),
        )
    }

    // SSO-only → still the chooser (one primary button beats auto-launching a browser), and the password
    // button must NOT exist — the form cannot work.
    @Test fun `sso-only routes to the method screen without a password button`() {
        assertEquals(
            Decision.Route(Step.METHOD, AlternativeSignIn.Oidc(null), supportsPassword = false),
            decide(abs(listOf("openid"))),
        )
    }

    // Password-only → skip the chooser entirely.
    @Test fun `password-only skips the method screen`() {
        assertEquals(
            Decision.Route(Step.PASSWORD, null, supportsPassword = true),
            decide(abs(listOf("local"))),
        )
    }

    // SSO advertised but refused over plaintext → not offered, so password is the only path.
    @Test fun `sso is not offered over plaintext`() {
        assertEquals(
            Decision.Route(Step.PASSWORD, null, supportsPassword = true),
            decide(abs(listOf("local", "openid")), isSecure = false),
        )
    }

    // Probe answered nothing usable → fail safe toward the password form.
    @Test fun `unknown capabilities fail safe toward password`() {
        assertEquals(
            Decision.Route(Step.PASSWORD, null, supportsPassword = true),
            decide(ServerCapabilities()),
        )
    }

    // The dead-end config: SSO-only over plaintext. We refuse SSO on http and the password form cannot
    // authenticate, so Connect must fail with the reason the user can act on (the scheme control).
    @Test fun `sso-only over plaintext blocks connect with insecure transport`() {
        assertEquals(
            Decision.Blocked(ConnectionError.InsecureTransport),
            decide(abs(listOf("openid")), isSecure = false),
        )
    }

    // Android-only gate: the browser leg needs Chrome 137+ (Auth Tab). A hard requirement, no fallback.
    @Test fun `sso is not offered without auth tab support`() {
        assertEquals(
            Decision.Route(Step.PASSWORD, null, supportsPassword = true),
            decide(abs(listOf("local", "openid")), ssoAvailable = false),
        )
    }

    @Test fun `sso-only without auth tab support blocks connect naming the browser requirement`() {
        assertEquals(
            Decision.Blocked(ConnectionError.SsoUnavailableOnDevice),
            decide(abs(listOf("openid")), ssoAvailable = false),
        )
    }

    // Plaintext is the reason named first: it is the one the user can fix on the address screen.
    @Test fun `plaintext wins over the browser requirement when both refuse sso`() {
        assertEquals(
            Decision.Blocked(ConnectionError.InsecureTransport),
            decide(abs(listOf("openid")), isSecure = false, ssoAvailable = false),
        )
    }

    @Test fun `the provider button label rides along`() {
        assertEquals(
            Decision.Route(Step.METHOD, AlternativeSignIn.Oidc("Login with Pocket ID"), supportsPassword = true),
            decide(abs(listOf("local", "openid"), buttonText = "Login with Pocket ID")),
        )
    }

    @Test fun `jellyfin with quick connect enabled routes to the method screen`() {
        assertEquals(
            Decision.Route(Step.METHOD, AlternativeSignIn.QuickConnect, supportsPassword = true),
            ConnectionRouting.decide(JELLYFIN, ServerCapabilities(quickConnectEnabled = true), isSecure = false, ssoAvailableOnDevice = false),
        )
    }

    @Test fun `jellyfin without quick connect goes straight to password`() {
        assertEquals(
            Decision.Route(Step.PASSWORD, null, supportsPassword = true),
            ConnectionRouting.decide(JELLYFIN, ServerCapabilities(), isSecure = true, ssoAvailableOnDevice = true),
        )
    }
}
