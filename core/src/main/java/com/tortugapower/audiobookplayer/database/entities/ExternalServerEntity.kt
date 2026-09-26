package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ExternalServiceType {
    JELLYFIN, AUDIOBOOKSHELF
}

@Entity(tableName = "external_servers")
data class ExternalServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ExternalServiceType,
    val url: String,
    val username: String? = null,
    val token: String? = null,
    val customHeaders: Map<String, String>? = null,
    // Which of the server's libraries the user browses; null = not chosen yet (service default).
    val selectedLibraryId: String? = null,
    // The server's SELF-REPORTED unique id, captured at connect/re-auth (Jellyfin System/Info Id,
    // ABS serverSettings.id). Cross-device stable — written as external resources' hostId so any
    // device with this server configured can resolve synced-down items. Null when never reported;
    // resolution then falls back to canonicalServerKey(url). NOT a credential: stays out of the
    // repository's encrypted()/decrypted() field set.
    val stableId: String? = null,
    // The account's id on the server (Jellyfin User.Id, ABS user.id), captured at sign-in. The
    // identity a re-auth matches on so the same account replaces its row while a second account on
    // the same server stays separate (iOS keys its store on url + userID). Null for rows saved
    // before the column existed; those fall back to matching on username. Not a credential.
    val userId: String? = null
)
