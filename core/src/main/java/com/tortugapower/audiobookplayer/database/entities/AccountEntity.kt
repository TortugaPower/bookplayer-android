package com.tortugapower.audiobookplayer.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AccountTier {
    FREE, PLUS, LITE, PRO
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val apiToken: String,
    val tier: AccountTier = AccountTier.FREE,
    val revenuecatId: String? = null
)
