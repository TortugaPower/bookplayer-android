package com.tortugapower.audiobookplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for [PlaybackManager.resolveSpeed]: which speed a book loads at (iOS `SpeedService.getSpeed`
 * and `LibraryService.getItemSpeed`). Global Speed Control on → the global speed; off → the folder's speed
 * for a book in a folder (`item.folder?.speed ?? item.speed`), else the book's own. Never set = 1x, as iOS
 * stores 1 by default.
 */
class SpeedResolutionTest {

    private fun resolve(
        globalSpeedControl: Boolean = false,
        globalSpeed: Float = 1.5f,
        itemSpeed: Double? = null,
        inFolder: Boolean = false,
        folderSpeed: Double? = null,
    ) = PlaybackManager.resolveSpeed(globalSpeedControl, globalSpeed, itemSpeed, inFolder, folderSpeed)

    @Test fun `global control on uses the global speed even when the book has its own`() {
        assertEquals(1.5f, resolve(globalSpeedControl = true, itemSpeed = 2.0, inFolder = true, folderSpeed = 1.25))
    }

    @Test fun `global control off uses the book's own speed`() {
        assertEquals(2.0f, resolve(itemSpeed = 2.0))
    }

    @Test fun `a book in a folder plays at the folder's speed`() {
        assertEquals(1.25f, resolve(itemSpeed = 2.0, inFolder = true, folderSpeed = 1.25))
    }

    @Test fun `a speed never set is 1x, not the global speed`() {
        assertEquals(1.0f, resolve(itemSpeed = null))
        assertEquals(1.0f, resolve(itemSpeed = 2.0, inFolder = true, folderSpeed = null))
    }

    /** iOS rounds every speed change: `round(speed * 100) / 100` */
    @Test fun `a set speed is kept to 2 decimals`() {
        assertEquals(1.3, PlaybackManager.roundedSpeed(1.0f + 0.1f + 0.1f + 0.1f), 0.0) // 1.3000001f
        assertEquals(2.0, PlaybackManager.roundedSpeed(2.0000002f), 0.0)
        assertEquals(1.25, PlaybackManager.roundedSpeed(1.25f), 0.0)
        assertEquals(0.5, PlaybackManager.roundedSpeed(0.5f), 0.0)
    }

    @Test fun `a stored speed with float noise loads rounded`() {
        assertEquals(1.3f, resolve(itemSpeed = 1.2999999523162842)) // iOS's Float sent as a Double
        assertEquals(1.3f, resolve(globalSpeedControl = true, globalSpeed = 1.3000001f))
        assertEquals(2.0f, resolve(inFolder = true, folderSpeed = 2.0000002384185791))
    }

    @Test fun `non-positive values count as 1x`() {
        assertEquals(1.0f, resolve(itemSpeed = 0.0))
        assertEquals(1.0f, resolve(globalSpeedControl = true, globalSpeed = 0f))
        assertEquals(1.0f, resolve(inFolder = true, folderSpeed = -1.0))
    }
}
