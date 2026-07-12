package com.vrcx.android.data.util

import java.time.Instant

/**
 * Parses a VRChat ISO-8601 timestamp into epoch milliseconds, returning null
 * when the string is absent or unparseable. Callers supply their own fallback
 * sentinel via `?:` (e.g. `Long.MIN_VALUE` for "sort oldest last").
 */
fun parseInstantMillisOrNull(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
}
