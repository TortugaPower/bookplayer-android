package com.tortugapower.audiobookplayer.logic

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tortugapower.audiobookplayer.core.CoreContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a transfer runs in the background: Android stops a backgrounded app otherwise.
 * Started when a transfer needs it (SyncKeepAlive), it stops itself once nothing has needed it for a minute. The
 * engine isn't here: it runs in the process (SyncRuntime), so this service builds nothing and a refused, timed-out
 * or stopped one leaves the engine running. Each target registers its own subclass, a manifest component with its
 * own notification: the phone's TaskConcurrencyServiceHost, the watch's WearSyncServiceHost.
 */
abstract class SyncKeepAliveService : Service() {

    companion object {
        private const val TAG = "SyncKeepAliveService"

        // How long nothing must need the service before it stops itself. Long enough to ride out the gap
        // between one download and the next, and a quick look at the app: stopped in the background, it
        // can't start again until the app leaves the screen once more. Short enough that the Android 15+
        // dataSync budget isn't spent for nothing.
        private const val IDLE_STOP_GRACE_MS = 60_000L

        private val _running = MutableStateFlow(false)

        /** Whether the service is up: it keeps the process alive, so no sync drain runs meanwhile (SyncKeepAlive) */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        // Cheap running check so bursty lane changes don't spam startForegroundService while the
        // service is already up. Racy by nature — a stale false just means one redundant,
        // idempotent start().
        private var isAlive: Boolean
            get() = _running.value
            set(value) { _running.value = value }

        /**
         * Told when Android won't let the service keep a transfer alive: its start was refused (the dataSync budget
         * spent, or no exemption), or it ended the service (the budget ran out). The transfer goes to sync drains
         * (SyncKeepAlive.onServiceUnavailable) until the app is next opened, which resets the budget.
         */
        @Volatile
        var onUnavailable: (() -> Unit)? = null

        /** Starts the target's keep-alive [service], unless it's up */
        fun start(context: Context, service: Class<out SyncKeepAliveService>) {
            if (isAlive) return
            val intent = Intent(context, service)
            // A background start of a dataSync FGS throws ForegroundServiceStartNotAllowedException
            // on API 31+ when no exemption covers it, or once the dataSync budget is spent. Swallow
            // it: the engine keeps running in the process meanwhile, and the next time the app leaves
            // the screen with a transfer running tries again (SyncKeepAlive) — never crash for it.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync service not started (likely a background start)", e)
                onUnavailable?.invoke()
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + StorageMonitor.exceptionHandler { CoreContext.appContextOrNull })

    /** The notification for the transfer lanes kept alive (none: nothing needs it, for the minute before it stops) */
    protected abstract fun notification(transfers: Set<String>): Notification

    /** Its notification channel (Android 8+), created before it promotes */
    protected abstract fun createNotificationChannel()

    /** Right before it promotes (the phone leaves a breadcrumb: a failed promotion's crash names no service) */
    protected open fun onPromoting() {}

    /** Android refused its promotion: the dataSync budget is spent */
    protected open fun onPromotionDenied(e: IllegalStateException) {}

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "⚙️ ${javaClass.simpleName}.onCreate()")
        isAlive = true
        createNotificationChannel()
        // A plain Application (tests) has no keep-alive: nothing to keep alive
        val transfers: StateFlow<Set<String>> =
            (application as? SyncRuntimeHost)?.syncKeepAlive?.transfers ?: MutableStateFlow(emptySet())

        // Promote FIRST. startForegroundService() gives us a few seconds to call startForeground(),
        // measured from the start request — and this runs on the main thread, which may be busy with
        // a recreate or an import sheet when the request lands. Anything before it risks
        // ForegroundServiceDidNotStartInTimeException (Sentry ANDROID-BOOKPLAYER-1H / -X).
        //
        // Android 15 gives dataSync services a 6h/day budget; once it's exhausted this throws
        // ForegroundServiceStartNotAllowedException ("time limit already exhausted") — and with
        // START_STICKY that used to be a crash LOOP (Play pre-launch review hit it: BOOKPLAYER-9).
        // Degrade instead: stop cleanly (which also satisfies the startForegroundService
        // obligation). There's nothing to tear down: the engine runs in the process, not here.
        onPromoting()
        try {
            startForeground(SyncServiceNotification.ID, notification(transfers.value))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground promotion denied (dataSync budget exhausted?): ${e.message}")
            onPromotionDenied(e)
            // Cleared BEFORE stopping (same as the idle-stop/onTimeout paths): a start() during
            // teardown must not be skipped by the fast-path.
            isAlive = false
            stopSelf()
            onUnavailable?.invoke()
            return
        }

        // Follow what the service keeps alive, for the notification, and stop once nothing needs it.
        // Android 15+ gives dataSync services a 6h/day budget that burns on wall-clock time, not work:
        // an always-on host exhausts it daily and gets killed mid-run (ANDROID-BOOKPLAYER-8) or refused
        // promotion (ANDROID-BOOKPLAYER-9). collectLatest makes the grace delay self-cancelling — a
        // transfer needing the service again restarts the block.
        serviceScope.launch {
            transfers.collectLatest { transfers ->
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(SyncServiceNotification.ID, notification(transfers))
                if (transfers.isEmpty()) {
                    delay(IDLE_STOP_GRACE_MS)
                    Log.d(TAG, "💤 Not needed for ${IDLE_STOP_GRACE_MS / 1000}s — stopping service to preserve the dataSync budget")
                    // Cleared BEFORE stopping so a lane going active mid-teardown isn't skipped by
                    // start()'s isAlive fast-path — its startForegroundService recreates us.
                    isAlive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    // NOT_STICKY: a sticky null-intent restart arrives from the BACKGROUND, where dataSync
    // promotion is refused (budget/exemption) — it can only churn, never do useful work. A transfer
    // needing the service starts it explicitly anyway (SyncKeepAlive).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // Android 15 calls this when the dataSync time budget runs out MID-RUN; not stopping within
    // a few seconds crashes the service ("did not stop within its timeout"). Demote SYNCHRONOUSLY
    // here — stopSelf() alone leaves the demotion to onDestroy, and any main-thread congestion
    // past the grace window is the ANDROID-BOOKPLAYER-8 crash. The engine keeps running in the
    // process until Android caches it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync budget expired (type=$fgsType) — stopping; the engine runs on in the process")
        isAlive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onUnavailable?.invoke()
    }

    override fun onDestroy() {
        isAlive = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
