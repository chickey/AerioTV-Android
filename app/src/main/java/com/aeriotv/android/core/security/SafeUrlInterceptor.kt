package com.aeriotv.android.core.security

import android.net.Uri
import android.util.Log
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * Coil interceptor that blocks image-loading requests whose source URL would
 * let a malicious playlist provider perform an SSRF-style probe or pull
 * untrusted content from a local scheme.
 *
 * Audit task #53. AerioTV renders logos / posters from URLs the playlist
 * source supplies (M3U `tvg-logo`, Dispatcharr channel logo, XMLTV
 * `<icon src=>`, VOD posters). All of those are attacker-influenced text
 * fields. Coil happily resolves `file://`, `content://`, `javascript:`,
 * `about:`, `data:text/html;...`, etc. — none of which we ever want for an
 * image cell.
 *
 * Policy:
 *   - http / https → pass through.
 *   - data:image/... → pass through (used by some EPG providers for inline
 *     channel logos).
 *   - everything else (file, content, javascript, about, raw data:text,
 *     custom schemes, etc.) → block with an [ErrorResult].
 *
 * Non-URL request data (drawable resources, ByteBuffers, ImageBitmaps
 * supplied directly by the app's own code) is unaffected; the interceptor
 * only inspects requests whose data parses as a URI with a scheme.
 *
 * Composable Coil 3 image painters that loaded a blocked URL show the
 * caller's fallback / placeholder, matching the behavior of any other
 * fetch failure — no crash, no UI surprise.
 */
class SafeUrlInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val candidateString = when (data) {
            is String -> data
            is Uri -> data.toString()
            is android.net.Uri -> data.toString()
            else -> null
        }
        if (candidateString != null) {
            val parsed = runCatching { Uri.parse(candidateString) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase()
            val blocked = when {
                scheme == null -> false // relative? let Coil handle / reject
                scheme == "http" || scheme == "https" -> false
                scheme == "data" -> {
                    // Allow only `data:image/...` -- everything else (text,
                    // html, application/...) has no business in an image cell.
                    !candidateString.startsWith("data:image", ignoreCase = true)
                }
                else -> true
            }
            if (blocked) {
                Log.w(TAG, "Blocked image fetch with scheme=$scheme")
                return ErrorResult(
                    image = null,
                    request = chain.request,
                    throwable = SecurityException("Image URL scheme not allowed: $scheme"),
                )
            }
        }
        return chain.proceed()
    }

    private companion object {
        const val TAG = "SafeUrlInterceptor"
    }
}
