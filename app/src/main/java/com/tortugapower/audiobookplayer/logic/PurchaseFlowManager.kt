package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.util.Log
import com.revenuecat.purchases.*
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PurchaseFlowManager {
    private const val TAG = "PurchaseFlowManager"
    private const val ENTITLEMENT_ID = "pro"
    const val MONTHLY_OFFERING_ID = "bookplayer.pro.monthly:pro-monthly"
    const val YEARLY_OFFERING_ID = "bookplayer.pro.year:pro-yearly"

    private val _isPurchasing = MutableStateFlow(false)
    val isPurchasing: StateFlow<Boolean> = _isPurchasing

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings: StateFlow<Offerings?> = _offerings

    fun fetchOfferings() {
        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                Log.d(TAG, "Offerings received. Current: ${offerings.current?.identifier}")
                offerings.all.forEach { (id, offering) ->
                    Log.d(TAG, "Available Offering: $id with ${offering.availablePackages.size} packages")
                }
                _offerings.value = offerings
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error fetching offerings: ${error.message}")
            }
        })
    }

    fun purchasePackage(activity: Activity, packageToPurchase: Package, onResult: (Boolean, String?) -> Unit) {
        _isPurchasing.value = true
        val params = PurchaseParams.Builder(activity, packageToPurchase).build()
        Purchases.sharedInstance.purchase(
            params,
            object : PurchaseCallback {
                override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                    _isPurchasing.value = false
                    // Force immediate update of SubscriptionManager state
                    SubscriptionManager.updateAccountTier(customerInfo)
                    
                    val subscribed = isSubscribed(customerInfo)
                    onResult(subscribed, null)
                }

                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    _isPurchasing.value = false
                    if (!userCancelled) {
                        onResult(false, error.message)
                    } else {
                        onResult(false, null)
                    }
                }
            }
        )
    }
    
    fun restorePurchases(onResult: (Boolean, String?) -> Unit) {
        Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                SubscriptionManager.updateAccountTier(customerInfo)
                val subscribed = isSubscribed(customerInfo)
                onResult(subscribed, null)
            }

            override fun onError(error: PurchasesError) {
                onResult(false, error.message)
            }
        })
    }

    private fun isSubscribed(customerInfo: CustomerInfo): Boolean {
        return customerInfo.entitlements["pro"]?.isActive == true ||
               customerInfo.entitlements["lite"]?.isActive == true ||
               customerInfo.entitlements["plus"]?.isActive == true
    }
}
