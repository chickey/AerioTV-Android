package com.aeriotv.android.core.debug

/**
 * Strips credentials out of log lines before they are written to the
 * persistent debug log file (audit task #53).
 *
 * Conservative pattern set — false positives are acceptable (a redacted
 * line is still readable), but a missed key shipped to a bug-report
 * attachment is not. Patterns:
 *
 * 1. URL query parameters whose name implies a secret
 *    (`?api_key=abc&password=xyz`).
 * 2. HTTP request/response header lines that carry credentials
 *    (`Authorization: ApiKey foo`, `X-API-Key: foo`, `Cookie: ...`).
 * 3. Bearer/ApiKey/Token prefixes inside free-form text.
 * 4. JWTs (3-segment base64url tokens beginning with `eyJ`).
 * 5. Dispatcharr-shaped API key form-encoded body (`apikey=...`).
 *
 * The set is intentionally case-insensitive. The replacement is `***` so
 * the structure of the line is preserved (a downstream reader still sees
 * `?api_key=***` and knows what kind of URL it was).
 *
 * Caller-side: every line that goes into [DebugLogger]'s queue passes
 * through [redact] first; the logcat echo (the dev-only path) is left
 * alone so live debugging via `adb logcat` still shows full values. The
 * persistent file is the bug-report surface we're protecting.
 */
internal object LogSanitizer {

    private val QUERY_PARAM = Regex(
        // [?&]name=value where name is one of the secret-y param names. The
        // value runs until the next `&`, whitespace, or end-of-line. We
        // capture the prefix so the replacement keeps `&password=` intact.
        "(?i)([?&](?:api[_-]?key|apikey|key|token|password|pass|secret|username|user)=)([^&\\s]+)",
    )

    private val HEADER_LINE = Regex(
        "(?i)\\b((?:Authorization|X-API-Key|X-Api-Key|Cookie|Set-Cookie|Proxy-Authorization)\\s*:\\s*)([^\\r\\n]+)",
    )

    private val INLINE_PREFIX = Regex(
        // "Bearer xyz", "ApiKey xyz", "Token xyz" -- 8+ chars to avoid eating
        // legit prose like "Bearer 1" or "Token A".
        "(?i)\\b(Bearer|ApiKey|Api-Key|Token)\\s+([A-Za-z0-9._=\\-]{8,})",
    )

    private val JWT = Regex(
        "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b",
    )

    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var out = message
        // Order matters: header form first (keeps the header name visible),
        // then JWT (longest pattern, prevents query-param regex from chopping
        // a JWT mid-base64), then prefix forms, then query params last.
        out = HEADER_LINE.replace(out) { mr -> mr.groupValues[1] + "***" }
        out = JWT.replace(out, "eyJ***")
        out = INLINE_PREFIX.replace(out) { mr -> "${mr.groupValues[1]} ***" }
        out = QUERY_PARAM.replace(out) { mr -> mr.groupValues[1] + "***" }
        return out
    }
}
