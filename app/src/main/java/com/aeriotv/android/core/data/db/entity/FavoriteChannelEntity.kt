package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-channel favorite flag. Mirrors iOS `favoriteChannelIDs` + `favoriteOrder`
 * @AppStorage arrays (architecture spec section C). `displayOrder` tracks the
 * user's manual reorder positions; defaults to addedAt so new favourites land
 * at the end of the existing list.
 */
@Entity(tableName = "favorite_channel")
data class FavoriteChannelEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val displayOrder: Long,
    val addedAt: Long,
)
