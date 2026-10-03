package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.AccountTier.FREE
import com.tortugapower.audiobookplayer.database.entities.AccountTier.LITE
import com.tortugapower.audiobookplayer.database.entities.AccountTier.PLUS
import com.tortugapower.audiobookplayer.database.entities.AccountTier.PRO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS `updateSyncEnabled`: what each reading means for the queue, against the one before it */
class TierTransitionsTest {

    @Test fun theFirstReading_isNoTransition_whateverItSays() {
        AccountTier.entries.forEach { assertEquals(TierChange.FirstReading, TierTransitions.classify(null, it)) }
    }

    @Test fun losingSync_isALapse_andPlusAloneDoesntSync() {
        assertEquals(TierChange.Lapse, TierTransitions.classify(PRO, FREE))
        assertEquals(TierChange.Lapse, TierTransitions.classify(PRO, PLUS))
        assertEquals(TierChange.Lapse, TierTransitions.classify(LITE, FREE))
        assertEquals(TierChange.Lapse, TierTransitions.classify(LITE, PLUS))
    }

    @Test fun gainingSync_isAReturn() {
        assertEquals(TierChange.Return, TierTransitions.classify(FREE, PRO))
        assertEquals(TierChange.Return, TierTransitions.classify(PLUS, LITE))
    }

    @Test fun betweenProAndLite_syncStaysOn() {
        assertEquals(TierChange.ProToLite, TierTransitions.classify(PRO, LITE))
        assertEquals(TierChange.LiteToPro, TierTransitions.classify(LITE, PRO))
    }

    @Test fun theSameTier_orTwoThatDontSync_changeNothing() {
        AccountTier.entries.forEach { assertEquals(TierChange.Unchanged, TierTransitions.classify(it, it)) }
        assertEquals(TierChange.Unchanged, TierTransitions.classify(FREE, PLUS))
        assertEquals(TierChange.Unchanged, TierTransitions.classify(PLUS, FREE))
    }

    @Test fun tierOf_takesTheHighestEntitlement() {
        assertEquals(PRO, TierTransitions.tierOf(hasPro = true, hasLite = true, hasPlus = true))
        assertEquals(LITE, TierTransitions.tierOf(hasPro = false, hasLite = true, hasPlus = true))
        assertEquals(PLUS, TierTransitions.tierOf(hasPro = false, hasLite = false, hasPlus = true))
        assertEquals(FREE, TierTransitions.tierOf(hasPro = false, hasLite = false, hasPlus = false))
    }

    /** What the engine may run now that the stored tier held: sync back, or file uploads */
    @Test fun releasesWork_whenSyncOrUploadsComeBack() {
        assertTrue(TierTransitions.releasesWork(FREE, PRO))
        assertTrue(TierTransitions.releasesWork(PLUS, LITE))
        assertTrue(TierTransitions.releasesWork(null, LITE))
        assertTrue(TierTransitions.releasesWork(LITE, PRO))
        assertFalse(TierTransitions.releasesWork(PRO, PRO))
        assertFalse(TierTransitions.releasesWork(PRO, LITE))
        assertFalse(TierTransitions.releasesWork(PRO, FREE))
        assertFalse(TierTransitions.releasesWork(FREE, PLUS))
    }
}
