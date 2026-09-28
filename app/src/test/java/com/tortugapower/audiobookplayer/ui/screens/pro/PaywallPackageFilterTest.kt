package com.tortugapower.audiobookplayer.ui.screens.pro

import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PresentedOfferingContext
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.Price
import com.revenuecat.purchases.models.PurchasingData
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.SubscriptionOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the paywall's product-tier ground truth: a sheet may only ever offer packages whose
 * PRODUCT id carries its tier keyword. The offering-level lookups trust RevenueCat dashboard
 * config, which has carried mixed tiers before — the 1.0.0 "Pro" sheet sold a user
 * bookplayer.lite.yearly (a tier with no cloud audio), stranding their uploads.
 */
class PaywallPackageFilterTest {

    private class FakeProduct(override val id: String) : StoreProduct {
        override val type = ProductType.SUBS
        override val price = Price("$1.00", 1_000_000, "USD")
        override val title = id
        override val name = id
        override val description = id
        override val period: Period? = null
        override val subscriptionOptions: SubscriptionOptions? = null
        override val defaultOption = null
        override val purchasingData = object : PurchasingData {
            override val productId = id
            override val productType = ProductType.SUBS
        }
        @Deprecated("Use presentedOfferingContext instead")
        override val presentedOfferingIdentifier: String? = null
        @Deprecated("Use presentedOfferingContext on Package instead")
        override val presentedOfferingContext: PresentedOfferingContext? = null
        @Deprecated("Use id instead")
        override val sku = id
        override fun copyWithOfferingId(offeringId: String): StoreProduct = this
        override fun copyWithPresentedOfferingContext(presentedOfferingContext: PresentedOfferingContext?): StoreProduct = this
    }

    private fun pkg(productId: String) = Package(
        identifier = productId,
        packageType = PackageType.ANNUAL,
        product = FakeProduct(productId),
        presentedOfferingContext = PresentedOfferingContext("test-offering"),
    )

    private val mixedOffering = listOf(
        pkg("bookplayer.pro.year"),
        pkg("bookplayer.lite.yearly"),
        pkg("bookplayer.pro.monthly"),
        pkg("bookplayer.lite.monthly"),
    )

    @Test
    fun `pro sheet never offers lite products even from a mixed offering`() {
        val result = filterPackagesByProduct(mixedOffering, "pro")
        assertEquals(listOf("bookplayer.pro.year", "bookplayer.pro.monthly"), result.map { it.product.id })
    }

    @Test
    fun `lite sheet never offers pro products even from a mixed offering`() {
        val result = filterPackagesByProduct(mixedOffering, "lite")
        assertEquals(listOf("bookplayer.lite.yearly", "bookplayer.lite.monthly"), result.map { it.product.id })
    }

    @Test
    fun `google product ids with base plan suffixes still match their tier`() {
        val result = filterPackagesByProduct(listOf(pkg("bookplayer.lite.yearly:lite-yearly")), "pro")
        assertEquals(emptyList<String>(), result.map { it.product.id })
    }
}
