package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.network.AlternativeSignIn
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ServerCapabilities

/**
 * The routing decision the connection flow hangs on: which screen Connect lands on, and which sign-in
 * methods that screen offers, given what the server reported and what this device can do. Pure so the
 * whole matrix is unit-tested (a server config you don't have renders a screen you never see).
 *
 * Mirrors iOS (`stepAfterConnect` + each view model's `alternativeSignIn`):
 * - an alternative exists → the method chooser, alternative primary, password secondary when supported;
 * - password only → straight to the password form;
 * - SSO advertised but refused (plain `http`, or no Auth Tab on this device) → simply not offered;
 * - no method can work (SSO-only server, refused SSO) → Connect fails with the reason, so the user
 *   stays on the address screen instead of landing on a form that cannot authenticate.
 */
object ConnectionRouting {
    enum class Step { METHOD, PASSWORD }

    sealed class Decision {
        data class Route(
            val step: Step,
            val alternativeSignIn: AlternativeSignIn?,
            val supportsPassword: Boolean,
        ) : Decision()

        data class Blocked(val error: ConnectionError) : Decision()
    }

    /**
     * @param isSecure whether the probed address is `https`. SSO is never offered over plaintext.
     * @param ssoAvailableOnDevice whether the browser leg can run here (Chrome 137+ Auth Tab). A hard
     *   requirement: without it SSO is not offered, and there is no fallback path.
     */
    fun decide(
        type: ExternalServiceType,
        capabilities: ServerCapabilities,
        isSecure: Boolean,
        ssoAvailableOnDevice: Boolean,
    ): Decision {
        val alternative: AlternativeSignIn? = when (type) {
            ExternalServiceType.JELLYFIN ->
                if (capabilities.quickConnectEnabled) AlternativeSignIn.QuickConnect else null
            ExternalServiceType.AUDIOBOOKSHELF ->
                if (capabilities.supportsOidc && isSecure && ssoAvailableOnDevice) {
                    AlternativeSignIn.Oidc(capabilities.oidcButtonText)
                } else {
                    null
                }
        }
        if (alternative == null && !capabilities.supportsPassword && capabilities.supportsOidc) {
            // SSO is the server's only method and we just refused it. Name the reason the user can act
            // on first: the scheme control is right there; the browser requirement is not fixable in-app.
            return Decision.Blocked(if (!isSecure) ConnectionError.InsecureTransport else ConnectionError.SsoUnavailableOnDevice)
        }
        return Decision.Route(
            step = if (alternative != null) Step.METHOD else Step.PASSWORD,
            alternativeSignIn = alternative,
            supportsPassword = capabilities.supportsPassword,
        )
    }
}
