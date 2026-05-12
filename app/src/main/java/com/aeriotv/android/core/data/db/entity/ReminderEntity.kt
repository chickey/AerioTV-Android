package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per scheduled programme reminder. Mirrors iOS `programReminders`
 * @AppStorage map keyed by "channelName|title|startTs" — same composite key
 * lives in [reminderKey] so the Android port preserves uniqueness semantics.
 *
 * The alarm itself is scheduled with AlarmManager at [startMillis] minus a
 * 5-minute pre-roll so users get a heads-up before the programme starts.
 */
@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey val reminderKey: String,
    val channelName: String,
    val programTitle: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Stable AlarmManager request code derived from `reminderKey.hashCode()`. */
    val alarmRequestCode: Int,
)

/** Composite key matching the iOS shape so a reminder's identity is portable. */
fun reminderKey(channelName: String, programTitle: String, startMillis: Long): String =
    "$channelName|$programTitle|$startMillis"
