package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.revenuecat.purchases.*
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private const val TIER_READY_TIMEOUT_MS = 10_000L
    private var accountRepository: AccountRepository? = null
    @Volatile private var tierSync: AccountTierSync? = null
    private val scope = CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob() +
            StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull }
    )

    // Completed once this launch's first tier reading is stored (before the queue is changed for it: waiting on the
    // hooks would let a pass holding the session lock stall a lapse's endSession), or there's none to wait for
    private val tierReady = CompletableDeferred<Unit>()

    /**
     * Play Store subscription-management deep link for the current customer, or null when
     * there's no store-managed subscription. The Android RC SDK has no `showManageSubscriptions()`
     * (unlike iOS); opening this URL is the equivalent. Compose-observable.
     */
    private val _managementUrl = MutableStateFlow<Uri?>(null)
    val managementUrl: StateFlow<Uri?> = _managementUrl.asStateFlow()

    fun initialize(
        context: Context,
        repository: AccountRepository,
        syncRepository: SyncTaskRepository,
        revenueCatApiKey: String,
        syncHooks: SyncSessionHooks? = null,
    ) {
        accountRepository = repository

        // At the beginning we work with RevenueCat sandbox
        Purchases.logLevel = LogLevel.DEBUG

        if (revenueCatApiKey.isEmpty()) {
            Log.e(TAG, "RevenueCat API Key is missing!")
            // No readings to come (dev builds): the stored tier is all there is
            tierReady.complete(Unit)
            return
        }

        Purchases.configure(
            PurchasesConfiguration.Builder(context, revenueCatApiKey)
                .build()
        )
        tierSync = AccountTierSync(repository, syncRepository, syncHooks).also {
            it.start(scope) { tierReady.complete(Unit) }
        }

        // This launch's first reading comes from RevenueCat's cache, as iOS reads its cached access level at
        // setup: a lapse it shows happened while the app was closed. With nothing cached, the stored tier stands
        // until a fetch answers. (Setting the listener below hands it the same cache, whichever lands first.)
        val launch = currentEpoch()
        Purchases.sharedInstance.getCustomerInfo(CacheFetchPolicy.CACHE_ONLY, object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                record(customerInfo, launch)
            }

            override fun onError(error: PurchasesError) {
                tierReady.complete(Unit)
            }
        })

        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
            Log.d(TAG, "Customer info updated")
            updateAccountTier(customerInfo)
        }

        // Initial sync
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                record(customerInfo, launch)
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

    /** Whether RevenueCat reads the tier: without it (dev builds), the stored one is all there is */
    val isConfigured: Boolean get() = Purchases.isConfigured

    /**
     * Returns once this launch's tier reading is stored, or there's none to wait for (a while at most). Until
     * then the stored tier is last session's: work it allows may be what a lapse while the app was closed holds.
     */
    suspend fun awaitTierReady() {
        withTimeoutOrNull(TIER_READY_TIMEOUT_MS) { tierReady.await() }
    }

    /** The watch's sign-in: a new account, then the same login as at launch */
    fun signIn(appUserId: String) {
        newEpoch()
        login(appUserId)
    }

    /** The signed-in account's RevenueCat user (launch, or a sign-in that began its epoch) */
    private fun login(appUserId: String) {
        if (!Purchases.isConfigured) return
        Log.d(TAG, "Logging in with appUserId: $appUserId")
        val started = currentEpoch()
        Purchases.sharedInstance.logIn(appUserId, object : LogInCallback {
            override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                record(customerInfo, started)
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
        val started = newEpoch()
        // Switching users — drop the previous customer's management URL up front so a login failure
        // (onError doesn't repopulate it) can't leave the UI pointing at a stale/incorrect
        // subscription-management link. It's set again from the fresh customer info on success.
        _managementUrl.value = null
        return suspendCancellableCoroutine { cont ->
            Purchases.sharedInstance.logIn(appUserId, object : LogInCallback {
                override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                    record(customerInfo, started)
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
        val started = newEpoch()
        Purchases.sharedInstance.logOut(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                record(customerInfo, started)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error logging out from RevenueCat: ${error.message}")
            }
        })
    }

    /**
     * A fresh read of the sync entitlement, for an account rejection from the API (iOS
     * `refreshSyncEntitlement`): true when PRO or LITE is active, false when neither is, null when
     * RevenueCat can't be reached. The tier is updated like on any read, so an inactive answer runs the
     * lapse path.
     */
    suspend fun refreshSyncEntitlement(): Boolean? {
        if (!Purchases.isConfigured) return null
        val started = currentEpoch()
        val info = try {
            Purchases.sharedInstance.awaitCustomerInfo(CacheFetchPolicy.FETCH_CURRENT)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't refresh the sync entitlement: ${e.message}")
            return null
        }
        record(info, started)
        return info.entitlements["pro"]?.isActive == true || info.entitlements["lite"]?.isActive == true
    }

    // Invoked from RevenueCat SDK callbacks and by the purchase/tip flows (in :app) after a purchase.
    fun updateAccountTier(customerInfo: CustomerInfo) = record(customerInfo)

    /** Hands a reading to [AccountTierSync]; one answering a call made before [startedIn]'s epoch ended is dropped */
    private fun record(customerInfo: CustomerInfo, startedIn: Int? = null) {
        val tier = TierTransitions.tierOf(
            hasPro = customerInfo.entitlements["pro"]?.isActive == true,
            hasLite = customerInfo.entitlements["lite"]?.isActive == true,
            hasPlus = customerInfo.entitlements["plus"]?.isActive == true,
        )
        // Always refreshed, even when the tier hasn't changed (a StateFlow: any thread)
        if (tierSync?.record(tier, startedIn) == true) _managementUrl.value = customerInfo.managementURL
    }

    private fun currentEpoch(): Int = tierSync?.currentEpoch() ?: 0

    private fun newEpoch(): Int = tierSync?.newEpoch() ?: 0
}
