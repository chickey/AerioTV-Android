package com.aeriotv.android.core.data

/**
 * Resolve EPG rows for a channel using progressively weaker keys.
 *
 * Preferred key is tvg-id. Some XC/M3U providers leave that blank or emit
 * IDs that don't match XMLTV channel ids, so we fall back to tvg-name and
 * display name normalization to keep guide data usable on those sources.
 */
fun Map<String, List<EPGProgramme>>.programmesFor(channel: M3UChannel): List<EPGProgramme> {
    val byId = channel.tvgID.trim()
    if (byId.isNotEmpty()) {
        val direct = this[byId]
        if (!direct.isNullOrEmpty()) return direct
    }

    val keys = listOf(channel.tvgName, channel.name)
        .asSequence()
        .map { it.normalizeEpgKey() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
    if (keys.isEmpty()) return emptyList()

    val matched = entries.firstOrNull { (k, _) ->
        val nk = k.normalizeEpgKey()
        nk.isNotEmpty() && nk in keys
    }
    return matched?.value.orEmpty()
}

private fun String.normalizeEpgKey(): String =
    lowercase()
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
