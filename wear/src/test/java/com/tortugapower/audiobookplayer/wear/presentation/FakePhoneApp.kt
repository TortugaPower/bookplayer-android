package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.wear.data.PhoneApp
import com.tortugapower.audiobookplayer.wear.data.PhoneAppStatus

/** A phone whose BookPlayer status and store answer the test sets; counts the checks it was asked for. */
internal class FakePhoneApp(
    var status: PhoneAppStatus = PhoneAppStatus.READY,
    var storeOpens: Boolean = true,
) : PhoneApp {
    var checks = 0
    var storeRequests = 0

    override suspend fun status(): PhoneAppStatus {
        checks++
        return status
    }

    override suspend fun openStoreListing(): Boolean {
        storeRequests++
        return storeOpens
    }
}
