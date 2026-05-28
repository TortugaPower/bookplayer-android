package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.*
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.tortugapower.audiobookplayer.BuildConfig
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private var accountRepository: AccountRepository? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context, repository: AccountRepository) {
        accountRepository = repository
        
        // At the beginning we work with RevenueCat sandbox
        Purchases.logLevel = LogLevel.DEBUG
        
        if (BuildConfig.REVENUECAT_API_KEY.isEmpty()) {
            Log.e(TAG, "RevenueCat API Key is missing!")
            return
        }

        Purchases.configure(
            PurchasesConfiguration.Builder(context, BuildConfig.REVENUECAT_API_KEY)
                .build()
        )

        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
            Log.d(TAG, "Customer info updated")
            updateAccountTier(customerInfo)
        }

        // Initial sync
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                updateAccountTier(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error fetching customer info: ${error.message}")
            }
        })

        // Check current account and log in if needed
        scope.launch {
            val account = repository.getAccount()
            if (account != null) {
                login(account.id)
            }
        }
    }

    fun login(appUserId: String) {
        if (!Purchases.isConfigured) return
        Log.d(TAG, "Logging in with appUserId: $appUserId")
        Purchases.sharedInstance.logIn(appUserId, object : LogInCallback {
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                updateAccountTier(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error logging in to RevenueCat: ${error.message}")
            }
        })
    }

    fun logout() {
        if (!Purchases.isConfigured) return
        Log.d(TAG, "Logging out")
        Purchases.sharedInstance.logOut(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                updateAccountTier(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error logging out from RevenueCat: ${error.message}")
            }
        })
    }

    private fun updateAccountTier(customerInfo: CustomerInfo) {
        // Temporarily disabled to prevent overwriting login subscription status
        /*
        val hasPro = customerInfo.entitlements["pro"]?.isActive == true
        val tier = if (hasPro) AccountTier.PRO else AccountTier.FREE
        
        Log.d(TAG, "Updating account tier. Has Pro: $hasPro")
        
        scope.launch {
            val account = accountRepository?.getAccount()
            if (account != null && account.tier != tier) {
                Log.d(TAG, "Persisting new tier: $tier")
                accountRepository?.saveAccount(account.copy(tier = tier))
            }
        }
        */
    }
}
