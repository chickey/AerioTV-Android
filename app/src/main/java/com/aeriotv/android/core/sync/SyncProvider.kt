package com.aeriotv.android.core.sync

import com.aeriotv.android.BuildConfig

/**
 * User-visible sync backends. The Fire flavour deliberately hides Google
 * services while the Play flavour can keep Drive sync available.
 */
enum class SyncProvider(val displayName: String) {
    None("None"),
    GoogleDrive("Google Drive"),
    Dispatcharr("Dispatcharr"),
}

object SyncProviders {
    val visible: List<SyncProvider>
        get() = if (BuildConfig.GOOGLE_SERVICES_AVAILABLE) {
            listOf(SyncProvider.None, SyncProvider.GoogleDrive, SyncProvider.Dispatcharr)
        } else {
            listOf(SyncProvider.None, SyncProvider.Dispatcharr)
        }

    val preferred: SyncProvider
        get() = if (BuildConfig.GOOGLE_SERVICES_AVAILABLE) SyncProvider.GoogleDrive else SyncProvider.Dispatcharr
}
