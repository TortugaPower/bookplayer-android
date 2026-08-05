package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// @SerializedName pins the Gson wire names: WatchAuthPayload carries the tier across the
// phone↔watch boundary, where each side is minified by its own R8 run. Room's generated enum
// converter is codegen (string literals baked at compile time), not reflection — no annotation
// needed for persistence, only for Gson.
enum class AccountTier {
    @SerializedName("FREE") FREE,
    @SerializedName("PLUS") PLUS,
    @SerializedName("LITE") LITE,
    @SerializedName("PRO") PRO
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val apiToken: String,
    val tier: AccountTier = AccountTier.FREE,
    val revenuecatId: String? = null
)
