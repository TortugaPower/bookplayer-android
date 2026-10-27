package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeDetectorTest {

    /** A network becoming the default, with its first capabilities: whether that reports a change */
    private fun NetworkChangeDetector<String>.becomesDefault(network: String, validated: Boolean = true): Boolean {
        onAvailable(network)
        return onCapabilitiesChanged(network, validated)
    }

    /** Registering reports the network already up: a sync service starting must not cut every backoff short */
    @Test fun theCallbackForTheNetworkAlreadyUp_isNoChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        assertFalse(detector.becomesDefault("wifi"))
    }

    @Test fun aSwitchBetweenWifiAndCellular_isAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.becomesDefault("wifi")

        assertTrue(detector.becomesDefault("cellular"))
        assertFalse("the same network again", detector.becomesDefault("cellular"))
        assertTrue(detector.becomesDefault("wifi"))
    }

    @Test fun aNetworkComingBackAfterALoss_isAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.onLost("wifi")
        assertTrue(detector.becomesDefault("wifi"))
    }

    @Test fun startedWithNoNetwork_theFirstOneIsAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering(null)
        assertTrue(detector.becomesDefault("cellular"))
    }

    @Test fun losingAnotherNetwork_keepsTheCurrentOne() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.onLost("cellular")
        assertFalse(detector.becomesDefault("wifi"))
    }

    /** Behind a captive portal, or right after a full loss: retried before it's validated, the tasks would fail again */
    @Test fun aNewNetwork_countsOnceItsValidated() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering(null)

        assertFalse(detector.becomesDefault("wifi", validated = false))
        assertFalse("capabilities change while not validated", detector.onCapabilitiesChanged("wifi", validated = false))
        assertTrue("signed in to the portal", detector.onCapabilitiesChanged("wifi", validated = true))
        assertFalse("reported once", detector.onCapabilitiesChanged("wifi", validated = true))
    }

    @Test fun aNetworkLostBeforeItsValidated_isNoChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("cellular")
        detector.becomesDefault("wifi", validated = false)
        detector.onLost("wifi")

        assertFalse(detector.onCapabilitiesChanged("wifi", validated = true))
    }

    @Test fun anotherNetworksCapabilities_areNoChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("cellular")
        detector.becomesDefault("wifi", validated = false)

        assertFalse(detector.onCapabilitiesChanged("cellular", validated = true))
        assertTrue(detector.onCapabilitiesChanged("wifi", validated = true))
    }
}
