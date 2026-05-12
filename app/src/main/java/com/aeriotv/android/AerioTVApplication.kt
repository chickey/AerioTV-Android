package com.aeriotv.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aeriotv.android.core.network.DispatcharrWarmupCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt Application + WorkManager Configuration.Provider so DriveSyncWorker can
 * have its Hilt-provided dependencies (DriveSyncManager, AppPreferences) injected
 * via @HiltWorker. Without the Configuration.Provider hook WorkManager auto-init
 * runs first and constructs workers via the default reflective factory, which
 * doesn't know about Hilt.
 *
 * Also binds the Dispatcharr warmup coordinator to the process-wide lifecycle
 * so JWT refresh runs on every foreground entry (cold launch + every time the
 * user returns from another app). Mirrors iOS scene-phase observer in
 * AerioApp.swift.
 */
@HiltAndroidApp
class AerioTVApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var dispatcharrWarmup: DispatcharrWarmupCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        dispatcharrWarmup.bind()
    }
}
