package com.aeriotv.android.core.sync

/**
 * Drive sync configuration. The user must supply their own OAuth 2.0 Web
 * Client ID (configured at https://console.cloud.google.com/apis/credentials)
 * before the Connect Google Drive button will produce a working sign-in. The
 * SHA-1 fingerprint of the signing certificate must also be registered with
 * an Android Client ID for the same project; only the web client id below
 * is fed to GoogleSignInOptions.requestIdToken / Drive.AppData scope.
 *
 * Distribution: this file ships empty so the open-source repo never embeds
 * a private OAuth client. To enable sync on a build:
 *
 *   1. Create a Google Cloud project, enable Drive API, set up the OAuth
 *      consent screen with the `https://www.googleapis.com/auth/drive.appdata`
 *      scope.
 *   2. Create OAuth client IDs: one Android (with your signing cert SHA-1)
 *      and one Web (used as the server client id for ID token requests).
 *   3. Paste the Web client id below or wire a BuildConfig field.
 */
object SyncConfig {

    /**
     * Web client id for Drive AppData OAuth. Empty until the user wires
     * their own Cloud project. SyncSettingsScreen surfaces a banner when
     * this is blank so the user knows what to do.
     */
    const val WEB_CLIENT_ID: String = ""

    fun isConfigured(): Boolean = WEB_CLIENT_ID.isNotBlank()

    /** Drive REST AppData scope. Files written here are scoped per-app. */
    const val DRIVE_APPDATA_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
}
