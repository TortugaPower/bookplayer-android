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
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private var accountRepository: AccountRepository? = null
    private var syncTaskRepository: SyncTaskRepository? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastProcessedTier: AccountTier? = null

    fun initialize(context: Context, repository: AccountRepository, syncRepository: SyncTaskRepository) {
        accountRepository = repository
        syncTaskRepository = syncRepository
        
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
                val rcId = account.revenuecatId ?: account.id
                login(rcId)
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
        val hasPro = customerInfo.entitlements["pro"]?.isActive == true
        val hasLite = customerInfo.entitlements["lite"]?.isActive == true
        val tier = when {
            hasPro -> AccountTier.PRO
            hasLite -> AccountTier.LITE
            else -> AccountTier.FREE
        }
        
        if (lastProcessedTier == tier) return
        lastProcessedTier = tier
        
        Log.d(TAG, "Updating account tier. Has Pro: $hasPro, Has Lite: $hasLite")
        
        scope.launch {
            val account = accountRepository?.getAccount()
            if (account != null && account.tier != tier) {
                Log.d(TAG, "Persisting new tier: $tier")
                accountRepository?.saveAccount(account.copy(tier = tier))
                
                // Trigger account-wide identifier sync on subscription activation
                if (tier == AccountTier.PRO || tier == AccountTier.LITE) {
                    syncTaskRepository?.let { repo ->
                        Log.d(TAG, "🚀 Subscription activated, triggering syncIdentifiers task")
                        SyncTaskFactory.createSyncIdentifiersTask(repo)
                    }
                }
            }
        }
    }
}
