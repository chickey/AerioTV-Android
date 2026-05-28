package com.aeriotv.android.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aeriotv.android.core.data.db.AerioDatabase
import com.aeriotv.android.core.data.db.dao.EpgProgrammeDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v10 -> v11: add the nullable `dispatcharrProfileId` column to `playlists`
     * for per-playlist Dispatcharr channel-profile scoping. A real ALTER (vs.
     * the destructive fallback) so existing saved playlists, credentials, and
     * LAN URLs survive the upgrade. SQLite ADD COLUMN with no default leaves
     * existing rows NULL = "All Channels", which is the prior behaviour.
     */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN dispatcharrProfileId INTEGER")
        }
    }

    /**
     * v11 -> v12: add episode metadata + the up-next queue to `watch_progress`
     * (iOS Issue #19 parity). Clean ALTERs so existing resume positions survive.
     * Defaults keep pre-v12 rows behaving as movies with no queue.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN vodType TEXT NOT NULL DEFAULT 'movie'")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN seriesId TEXT")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN seasonNumber INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN episodeNumber INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN streamUrl TEXT")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN isFinished INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE watch_progress ADD COLUMN upNextQueue TEXT")
        }
    }

    /**
     * v12 -> v13: add the `epg_programme` disk cache (iOS GuideStore parity) so a
     * relaunch can paint now-playing + the guide instantly from the last fetch.
     * A CREATE TABLE (not the destructive fallback, which would drop the user's
     * playlists / favorites / watch-progress) since those tables are untouched.
     * The table + index definitions must match what Room generates for
     * EpgProgrammeEntity, including the auto-named indices.
     */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programme` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`playlistId` TEXT NOT NULL, " +
                    "`channelId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`startMillis` INTEGER NOT NULL, " +
                    "`endMillis` INTEGER NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`dispatcharrProgramId` INTEGER, " +
                    "`fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_playlistId` ON `epg_programme` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_playlistId_channelId` ON `epg_programme` (`playlistId`, `channelId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programme_endMillis` ON `epg_programme` (`endMillis`)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AerioDatabase =
        Room.databaseBuilder(context, AerioDatabase::class.java, "aerio.db")
            // Preserve user data across known schema bumps where a clean ALTER
            // exists; fall back to a destructive rebuild only for un-mapped
            // version jumps (older dev builds).
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePlaylistDao(db: AerioDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideWatchProgressDao(db: AerioDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideLocalRecordingDao(db: AerioDatabase): LocalRecordingDao = db.localRecordingDao()

    @Provides
    fun provideFavoriteChannelDao(db: AerioDatabase): FavoriteChannelDao = db.favoriteChannelDao()

    @Provides
    fun provideReminderDao(db: AerioDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideEpgProgrammeDao(db: AerioDatabase): EpgProgrammeDao = db.epgProgrammeDao()
}
