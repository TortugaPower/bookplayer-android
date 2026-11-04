package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The API key a request sends: older versions stored it as pasted, `Bearer ` prefix included, and the request adds
 * its own, so the stored value is cleaned up when it's read (else every call sends `Bearer Bearer …` and gets a 401).
 */
class HardcoverApiKeyTest {
    @Test fun `a key saved with the Bearer prefix by an older version is sent bare`() {
        assertEquals("hc_pat_abc", HardcoverAccount.apiKey("Bearer hc_pat_abc"))
        assertEquals("eyJhbGciOi", HardcoverAccount.apiKey("bearer   eyJhbGciOi"))
    }

    @Test fun `a bare key is unchanged, surrounding space trimmed`() {
        assertEquals("hc_pat_abc", HardcoverAccount.apiKey("hc_pat_abc"))
        assertEquals("hc_pat_abc", HardcoverAccount.apiKey("  hc_pat_abc \n"))
    }

    @Test fun `a prefix alone or a blank value is no key`() {
        assertEquals("", HardcoverAccount.apiKey("Bearer "))
        assertEquals("", HardcoverAccount.apiKey("   "))
    }
}
