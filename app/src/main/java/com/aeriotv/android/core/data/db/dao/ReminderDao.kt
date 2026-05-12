package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)

    @Query("DELETE FROM reminder WHERE reminderKey = :reminderKey")
    suspend fun delete(reminderKey: String)

    @Query("SELECT * FROM reminder WHERE reminderKey = :reminderKey LIMIT 1")
    suspend fun getOnce(reminderKey: String): ReminderEntity?

    @Query("SELECT * FROM reminder ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM reminder WHERE reminderKey = :reminderKey)")
    fun observeIsSet(reminderKey: String): Flow<Boolean>

    @Query("SELECT * FROM reminder ORDER BY startMillis ASC")
    suspend fun allOnce(): List<ReminderEntity>

    @Query("DELETE FROM reminder")
    suspend fun clear()
}
