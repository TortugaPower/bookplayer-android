package com.tortugapower.audiobookplayer.logic

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One selectable launcher icon, backed by an `<activity-alias>` in the manifest.
 *
 * @param id stable key that doubles as the alias suffix — the alias component is
 *           `"$packageName.icon.$id"` (e.g. `com.tortugapower.audiobookplayer.icon.Retro`). Keep in
 *           sync with `AndroidManifest.xml`.
 * @param titleRes display name shown in the picker
 * @param free whether the icon is available without a paid tier (only Default + Retro)
 * @param previewRes the mipmap shown in the picker — the same drawable the alias declares as its
 *                   `android:icon`
 */
data class AppIcon(
    val id: String,
    @StringRes val titleRes: Int,
    val free: Boolean,
    @DrawableRes val previewRes: Int,
)
