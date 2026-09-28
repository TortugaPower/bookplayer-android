package com.tortugapower.audiobookplayer.logic

/**
 * Whether the sync host should be started at app launch: only for work left over from an earlier
 * session — a PENDING task, or a RUNNING one a killed process left behind (the engine resets those
 * when it starts). Everything enqueued from here on wakes the host itself (`SyncEngineWaker`: the
 * library's fetch, imports, downloads), so an unconditional start only ever promoted a dataSync
 * foreground service to sit "Idle" for a minute — on every launch, for every user, including
 * logged-out ones with nothing to sync — burning the Android 15 dataSync budget for nothing and, as a
 * side effect, masking ANDROID-BOOKPLAYER-21 for that minute (any foreground service of ours lets the
 * playback service promote from the background). iOS runs its sync service only for an account.
 */
object SyncHostLaunchGate {
    /** [activeTasks] counts PENDING + RUNNING tasks (`SyncTaskRepository.countActiveTasks`). */
    suspend fun shouldStart(activeTasks: suspend () -> Int): Boolean = activeTasks() > 0
}
