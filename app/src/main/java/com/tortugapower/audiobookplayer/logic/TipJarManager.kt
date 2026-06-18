package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Outcome of a tip purchase. */
sealed interface TipResult {
    data object Success : TipResult
    data object Cancelled : TipResult
    data class Error(val message: String?) : TipResult
}

/** Outcome of restoring tips. */
sealed interface RestoreResult {
    data object Restored : RestoreResult
    data object NothingToRestore : RestoreResult
    data class Error(val message: String?) : RestoreResult
}

/**
 * Tip Jar purchases via RevenueCat. Tips are configured (RevenueCat dashboard) to grant the `plus`
 * entitlement, which the existing [SubscriptionManager.updateAccountTier] maps to
 * [com.tortugapower.audiobookplayer.database.entities.AccountTier.PLUS]. Tips bypass offerings and
 * are fetched directly by product id (mirrors iOS).
 *
 * All entry points are `suspend` (RevenueCat is callback-based; wrapped here) — call from a scope.
 */
object TipJarManager {
    private const val TAG = "TipJarManager"

    /** Localized prices keyed by tier (falls back to [TipTier.fallbackPrice] if a product is missing). */
    suspend fun fetchPrices(): Map<TipTier, String> {
        if (!Purchases.isConfigured) return emptyMap()
        val byId = awaitProducts(TipTier.entries.map { it.baseProductId }).associateBy { it.id }
        return TipTier.entries.associateWith { tier ->
            byId[tier.baseProductId]?.price?.formatted ?: tier.fallbackPrice
        }
    }

    /** True when the user has no active `plus` entitlement yet — drives non-consumable vs consumable. */
    suspend fun isFirstDonation(): Boolean {
        val info = awaitCustomerInfo() ?: return true
        return info.entitlements["plus"]?.isActive != true
    }

    suspend fun purchaseTip(activity: Activity, tier: TipTier, isFirstDonation: Boolean): TipResult {
        if (!Purchases.isConfigured) return TipResult.Error(null)
        val product = awaitProducts(listOf(tier.productId(isFirstDonation))).firstOrNull()
            ?: return TipResult.Error(null)
        return suspendCancellableCoroutine { cont ->
            val params = PurchaseParams.Builder(activity, product).build()
            Purchases.sharedInstance.purchase(params, object : PurchaseCallback {
                override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                    // Reflect the freshly granted `plus` entitlement immediately (callback is main-thread).
                    SubscriptionManager.updateAccountTier(customerInfo)
                    if (cont.isActive) cont.resume(TipResult.Success)
                }

                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    if (cont.isActive) {
                        cont.resume(if (userCancelled) TipResult.Cancelled else TipResult.Error(error.message))
                    }
                }
            })
        }
    }

    suspend fun restoreTips(): RestoreResult {
        if (!Purchases.isConfigured) return RestoreResult.Error(null)
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    SubscriptionManager.updateAccountTier(customerInfo)
                    // Tips grant the `plus` entitlement, so a restored tip = `plus` active. (A Pro
                    // subscription is not a tip, so it doesn't count as "restored" here.)
                    val tipRestored = customerInfo.entitlements["plus"]?.isActive == true
                    if (cont.isActive) {
                        cont.resume(if (tipRestored) RestoreResult.Restored else RestoreResult.NothingToRestore)
                    }
                }

                override fun onError(error: PurchasesError) {
                    Log.e(TAG, "restore error: ${error.message}")
                    // Surface the store's message (e.g. "billing unavailable") like the Pro restore does.
                    if (cont.isActive) cont.resume(RestoreResult.Error(error.message))
                }
            })
        }
    }

    private suspend fun awaitProducts(ids: List<String>): List<StoreProduct> =
        suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getProducts(ids, ProductType.INAPP, object : GetStoreProductsCallback {
                override fun onReceived(storeProducts: List<StoreProduct>) {
                    if (cont.isActive) cont.resume(storeProducts)
                }

                override fun onError(error: PurchasesError) {
                    Log.e(TAG, "getProducts error: ${error.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }

    private suspend fun awaitCustomerInfo(): CustomerInfo? =
        suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    if (cont.isActive) cont.resume(customerInfo)
                }

                override fun onError(error: PurchasesError) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
}
