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
    if (matched?.value?.isNotEmpty() == true) return matched.value

    // Extra fallback (mainly for XC/odd XMLTV feeds): try channel-number joins.
    // Some providers emit guide channel ids like "101", "101.0", or "ch-101"
    // while M3U names/ids differ. We only use this after tvg-id + name matching
    // fails, so standard providers stay on the canonical path.
    val num = channel.channelNumber?.trim().orEmpty()
    if (num.isEmpty()) return emptyList()

    val numericVariants = buildList {
        add(num)
        num.toDoubleOrNull()?.let { d ->
            val i = d.toInt()
            if (d == i.toDouble()) {
                add(i.toString())
                add("$i.0")
            }
        }
    }.distinct()

    // 1) Exact key hit first.
    numericVariants.forEach { candidate ->
        val directNum = this[candidate]
        if (!directNum.isNullOrEmpty()) return directNum
    }

    // 2) Normalized textual hit (covers keys like "ch 101").
    val normalizedCandidates = numericVariants
        .map { it.normalizeEpgKey() }
        .filter { it.isNotEmpty() }
        .toSet()
    val numericMatched = entries.firstOrNull { (k, _) ->
        val nk = k.normalizeEpgKey()
        nk.isNotEmpty() && nk in normalizedCandidates
    }
    return numericMatched?.value.orEmpty()
}

private fun String.normalizeEpgKey(): String =
    lowercase()
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
