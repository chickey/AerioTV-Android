package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.EpgProgrammeEntity

@Dao
interface EpgProgrammeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpgProgrammeEntity>)

    @Query("SELECT * FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: String): List<EpgProgrammeEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    @Query("DELETE FROM epg_programme WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /** Prune programmes that have already ended (hygiene across all sources). */
    @Query("DELETE FROM epg_programme WHERE endMillis < :before")
    suspend fun deleteEndedBefore(before: Long)

    /**
     * Replace the whole cached guide for one source in a single transaction so a
     * reader never sees a half-written batch. Mirrors iOS GuideStore.saveToCache.
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: String, rows: List<EpgProgrammeEntity>) {
        deleteForPlaylist(playlistId)
        insertAll(rows)
    }
}
