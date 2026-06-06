package com.aeriotv.android.core.update

import com.aeriotv.android.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.client.request.accept
import io.ktor.serialization.kotlinx.json.json
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Information about an available update fetched from GitHub releases.
 */
data class UpdateInfo(
    /** Raw GitHub tag name, e.g. "v0.1.8-fire". */
    val tagName: String,
    /** Human-readable version, e.g. "0.1.8". */
    val versionName: String,
    /** Truncated release notes from the GitHub release body. */
    val releaseNotes: String,
)

/**
 * Checks the GitHub releases API for a newer version of AerioTV.
 *
 * Only meaningful in the Fire TV flavor ([BuildConfig.GITHUB_UPDATES_ENABLED]).
 * Version comparison is semver-style: splits "v0.1.8-fire" into [0, 1, 8]
 * and compares component-by-component against [BuildConfig.VERSION_NAME].
 */
@Singleton
class GithubUpdateChecker @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        install(ContentNegotiation) { json(json) }
    }

    /**
     * Returns an [UpdateInfo] if GitHub has a newer release than the running
     * build, or `null` if already up to date. Throws on network or parse error.
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        val response = client.get(apiUrl()) {
            accept(ContentType.Application.Json)
            // GitHub API requires a User-Agent header.
            header("User-Agent", "AerioTV-Android/${BuildConfig.VERSION_NAME}")
        }
        val release = response.body<GithubRelease>()
        return if (isNewerThanCurrent(release.tagName)) {
            UpdateInfo(
                tagName = release.tagName,
                versionName = cleanVersion(release.tagName),
                releaseNotes = release.body.orEmpty()
                    .lines()
                    .take(12)           // cap at ~12 lines to fit the dialog
                    .joinToString("\n")
                    .trim(),
            )
        } else {
            null
        }
    }

    // -------------------------------------------------------------------------

    private fun apiUrl() =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    private fun cleanVersion(tag: String): String =
        tag.removePrefix("v").removeSuffix("-fire")

    private fun isNewerThanCurrent(remoteTag: String): Boolean {
        val remote = parseVersionParts(remoteTag) ?: return false
        val local = parseVersionParts(BuildConfig.VERSION_NAME) ?: return false
        val len = maxOf(remote.size, local.size)
        for (i in 0 until len) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int>? {
        val clean = version.removePrefix("v").removeSuffix("-fire")
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        return parts.ifEmpty { null }
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
    )
}
