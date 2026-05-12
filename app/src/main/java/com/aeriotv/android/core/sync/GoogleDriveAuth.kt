package com.aeriotv.android.core.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Sign-In + authorization flow for Drive AppData access. Uses the
 * modern AuthorizationClient (com.google.android.gms.auth.api.identity) which
 * supersedes the older GoogleSignIn API and works with the Web Client Id
 * model preferred for cross-platform OAuth.
 *
 * Two-step:
 *   1. [requestAuthorization] returns either an [AuthorizationResult] with a
 *      ready access token, or an [IntentSender] the activity has to launch
 *      so the user can grant consent.
 *   2. [extractAccessToken] pulls the bearer token out of the activity result
 *      intent once the consent screen returns OK.
 *
 * NOTE: Caller must have a configured Web Client ID via [SyncConfig]. Without
 * it Identity.getAuthorizationClient still returns a client, but consent will
 * fail with API_NOT_CONNECTED or DEVELOPER_ERROR depending on platform state.
 */
class GoogleDriveAuth(private val context: Context) {

    suspend fun requestAuthorization(): AuthorizationResult? {
        if (!SyncConfig.isConfigured()) {
            Log.w(TAG, "SyncConfig.WEB_CLIENT_ID is blank — aborting authorization request")
            return null
        }
        val client = Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SyncConfig.DRIVE_APPDATA_SCOPE)))
            .requestOfflineAccess(SyncConfig.WEB_CLIENT_ID, true)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.authorize(request)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { t ->
                    Log.w(TAG, "authorize() failed", t)
                    if (cont.isActive) cont.resumeWithException(t)
                }
        }
    }

    /**
     * Parse the activity result from a consent flow. Returns the access token
     * (Bearer) if the user granted, null on cancel or parse failure.
     */
    fun extractAccessToken(data: Intent?): String? {
        if (data == null) return null
        return runCatching {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            result.accessToken
        }.onFailure { Log.w(TAG, "extractAccessToken failed", it) }
            .getOrNull()
    }

    companion object { private const val TAG = "GoogleDriveAuth" }
}
