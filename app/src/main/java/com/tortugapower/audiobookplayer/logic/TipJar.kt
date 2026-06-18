package com.tortugapower.audiobookplayer.logic

import androidx.annotation.StringRes
import com.tortugapower.audiobookplayer.R

/**
 * The three tip tiers, mirroring iOS (`TipOption`). Each maps to a Play Console one-time product.
 *
 * The **first** tip a user gives uses the non-consumable [baseProductId] — it's acknowledged (not
 * consumed), so it's restorable and anchors the durable RevenueCat `plus` entitlement. **Repeat**
 * tips use the `.consumable` twin so they can be bought again. See [productId].
 */
enum class TipTier(
    val baseProductId: String,
    val fallbackPrice: String,
    @StringRes val titleRes: Int,
    /** Per-tier pill color (fixed brand colors, matching iOS). */
    val accentArgb: Long,
) {
    KIND("bookplayer.tip.kind", "$2.99", R.string.tip_kind_title, 0xFF528CCD),
    EXCELLENT("bookplayer.tip.excellent", "$4.99", R.string.tip_excellent_title, 0xFF687AB7),
    INCREDIBLE("bookplayer.tip.incredible", "$9.99", R.string.tip_incredible_title, 0xFF6565AB);

    fun productId(isFirstDonation: Boolean): String =
        if (isFirstDonation) baseProductId else "$baseProductId.consumable"
}
