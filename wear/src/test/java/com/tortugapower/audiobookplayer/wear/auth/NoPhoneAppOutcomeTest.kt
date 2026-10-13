package com.tortugapower.audiobookplayer.wear.auth

import com.tortugapower.audiobookplayer.wear.data.PhoneAppStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NoPhoneAppOutcomeTest {

    @Test fun appMissing_offersTheInstall() {
        assertEquals(WearAuthOutcome.PhoneAppMissing, noPhoneAppOutcome(PhoneAppStatus.APP_MISSING))
    }

    @Test fun noPhone_orTheAppShowingUpMeanwhile_isARetry() {
        assertEquals(WearAuthOutcome.PhoneNotReachable, noPhoneAppOutcome(PhoneAppStatus.NO_PHONE))
        assertEquals(WearAuthOutcome.PhoneNotReachable, noPhoneAppOutcome(PhoneAppStatus.READY))
    }
}
