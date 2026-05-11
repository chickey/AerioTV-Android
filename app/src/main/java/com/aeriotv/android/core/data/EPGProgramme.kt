package com.aeriotv.android.core.data

/**
 * Single XMLTV `<programme>` entry. Mirrors iOS ParsedEPGProgram
 * (Aerio/Networking/PlaylistParsers.swift around line 263).
 *
 * Times are Unix epoch milliseconds (UTC) for cross-timezone consistency.
 * [channelId] matches an M3UChannel.tvgID for the join.
 */
data class EPGProgramme(
    val channelId: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val category: String,
)
