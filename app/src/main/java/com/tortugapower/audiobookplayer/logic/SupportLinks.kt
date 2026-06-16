package com.tortugapower.audiobookplayer.logic

/**
 * Canonical support destinations, shared across platforms (mirrors iOS `SettingsSupportSectionView`).
 * Lives in `logic` (not `ui`) since it's consumed by both the UI and `SupportInfo`, so both depend
 * downward.
 */
object SupportLinks {
    const val GITHUB = "https://github.com/TortugaPower/bookplayer-android"
    const val DISCORD = "https://discord.gg/RPPyhyMPXW"
    const val SUPPORT_EMAIL = "support@bookplayer.app"
}
