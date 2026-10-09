package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeDetectorTest {

    /** Registering reports the network already up: a sync service starting must not cut every backoff short */
    @Test fun theCallbackForTheNetworkAlreadyUp_isNoChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        assertFalse(detector.onAvailable("wifi"))
    }

    @Test fun aSwitchBetweenWifiAndCellular_isAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.onAvailable("wifi")

        assertTrue(detector.onAvailable("cellular"))
        assertFalse("the same network again", detector.onAvailable("cellular"))
        assertTrue(detector.onAvailable("wifi"))
    }

    @Test fun aNetworkComingBackAfterALoss_isAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.onLost("wifi")
        assertTrue(detector.onAvailable("wifi"))
    }

    @Test fun startedWithNoNetwork_theFirstOneIsAChange() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering(null)
        assertTrue(detector.onAvailable("cellular"))
    }

    @Test fun losingAnotherNetwork_keepsTheCurrentOne() {
        val detector = NetworkChangeDetector<String>()
        detector.onRegistering("wifi")
        detector.onLost("cellular")
        assertFalse(detector.onAvailable("wifi"))
    }
}
