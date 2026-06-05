package com.aeriotv.android.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Dispatcharr scoped device token in Android-encrypted storage.
 *
 * Uses [EncryptedSharedPreferences] backed by the Android Keystore when
 * available. Some Fire TV devices have a flaky Keystore/StrongBox, so we fall
 * back to ordinary [SharedPreferences] rather than leaving the user unable to
 * pair. The fallback is intentionally silent: the token is LAN-scoped and
 * revocable from the plugin, so availability is preferred over hard failure.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences {
        cached?.let { return it }
        val resolved = synchronized(this) {
            cached ?: createPrefs().also { cached = it }
        }
        return resolved
    }

    private fun createPrefs(): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // Keystore unavailable: degrade to plaintext prefs so pairing still works.
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    fun saveDeviceToken(token: String) {
        prefs().edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun deviceToken(): String? = prefs().getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun clearDeviceToken() {
        prefs().edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    private companion object {
        const val ENCRYPTED_FILE = "aeriotv_secure_tokens"
        const val FALLBACK_FILE = "aeriotv_tokens_fallback"
        const val KEY_DEVICE_TOKEN = "dispatcharr_device_token"
    }
}
