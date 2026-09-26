package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.revenuecat.purchases.*
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private var accountRepository: AccountRepository? = null
    private var syncTaskRepository: SyncTaskRepository? = null
    private val scope = CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob() +
            StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull }
    )
    private var lastProcessedTier: AccountTier? = null

    /**
     * Play Store subscription-management deep link for the current customer, or null when
     * there's no store-managed subscription. The Android RC SDK has no `showManageSubscriptions()`
     * (unlike iOS); opening this URL is the equivalent. Compose-observable.
     */
    private val _managementUrl = MutableStateFlow<Uri?>(null)
    val managementUrl: StateFlow<Uri?> = _managementUrl.asStateFlow()

    fun initialize(context: Context, repository: AccountRepository, syncRepository: SyncTaskRepository, revenueCatApiKey: String) {
        accountRepository = repository
        syncTaskRepository = syncRepository

        // At the beginning we work with RevenueCat sandbox
        Purchases.logLevel = LogLevel.DEBUG

        if (revenueCatApiKey.isEmpty()) {
            Log.e(TAG, "RevenueCat API Key is missing!")
            return
        }

        Purchases.configure(
            PurchasesConfiguration.Builder(context, revenueCatApiKey)
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
        lastProcessedTier = null // Clear to force re-evaluation for the new user
        Purchases.sharedInstance.logIn(appUserId, object : LogInCallback {
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                updateAccountTier(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error logging in to RevenueCat: ${error.message}")
            }
        })
    }

    /**
     * Logs into RevenueCat and suspends until the customer info is available, then reports
     * whether the user has an active (paid) subscription. Mirrors iOS, which awaits
     * `Purchases.logIn` and reads `customerInfo.activeSubscriptions`. Used by the auth flow to
     * decide whether to present the "Complete Your Account" paywall. Returns false if RevenueCat
     * isn't configured or the call fails (so the paywall is shown — the safe default).
     */
    suspend fun loginAndCheckSubscription(appUserId: String): Boolean {
        if (!Purchases.isConfigured) return false
        lastProcessedTier = null
        // Switching users — drop the previous customer's management URL up front so a login failure
        // (onError doesn't repopulate it) can't leave the UI pointing at a stale/incorrect
        // subscription-management link. It's set again from the fresh customer info on success.
        _managementUrl.value = null
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.logIn(appUserId, object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                    updateAccountTier(customerInfo)
                    // Guard against resuming a continuation that was already cancelled (e.g. the
                    // caller's scope was cleared before RevenueCat's callback fired) — resuming a
                    // cancelled/completed continuation throws.
                    if (cont.isActive) cont.resume(customerInfo.activeSubscriptions.isNotEmpty())
                }

                override fun onError(error: PurchasesError) {
                    Log.e(TAG, "Error logging in to RevenueCat: ${error.message}")
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    fun logout() {
        if (!Purchases.isConfigured) return
        Log.d(TAG, "Logging out")
        lastProcessedTier = null
        Purchases.sharedInstance.logOut(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                updateAccountTier(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error logging out from RevenueCat: ${error.message}")
            }
        })
    }

    // Invoked from RevenueCat SDK callbacks and by the purchase/tip flows (in :app) after a purchase.
    // `_managementUrl` is a StateFlow, so its write is thread-safe regardless of the calling thread.
    fun updateAccountTier(customerInfo: CustomerInfo) {
        // Always refresh the management URL, even when the tier hasn't changed.
        _managementUrl.value = customerInfo.managementURL

        val activeEntitlements = customerInfo.entitlements.active.keys

        val hasPro = customerInfo.entitlements["pro"]?.isActive == true
        val hasLite = customerInfo.entitlements["lite"]?.isActive == true
        val hasPlus = customerInfo.entitlements["plus"]?.isActive == true

        val tier = when {
            hasPro -> AccountTier.PRO
            hasLite -> AccountTier.LITE
            hasPlus -> AccountTier.PLUS
            else -> AccountTier.FREE
        }
        
        if (lastProcessedTier == tier) return
        lastProcessedTier = tier
        
        Log.d(TAG, "Setting account tier to: $tier (Pro: $hasPro, Lite: $hasLite, Plus: $hasPlus)")
        
        scope.launch {
            val account = accountRepository?.getAccount()
            if (account != null && account.tier != tier) {
                Log.d(TAG, "Persisting new tier: $tier for account: ${account.email}")
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
