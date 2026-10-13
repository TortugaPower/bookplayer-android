package com.tortugapower.audiobookplayer.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAppStatusTest {

    @Test fun phoneAppAdvertised_isReady() {
        assertEquals(PhoneAppStatus.READY, phoneAppStatus(appReachable = true, nodeConnected = true))
    }

    @Test fun connectedPhoneWithoutTheApp_isAppMissing() {
        assertEquals(PhoneAppStatus.APP_MISSING, phoneAppStatus(appReachable = false, nodeConnected = true))
    }

    @Test fun nothingConnected_isNoPhone() {
        assertEquals(PhoneAppStatus.NO_PHONE, phoneAppStatus(appReachable = false, nodeConnected = false))
    }
}
