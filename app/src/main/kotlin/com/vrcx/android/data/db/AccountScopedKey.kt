package com.vrcx.android.data.db

/**
 * Per-account Room rows (memos, notes, friend-notify flags, friend-log entries)
 * share a single table across every logged-in account and disambiguate with a
 * composite primary key of `ownerUserId:entityId`.
 *
 * The format lives here so no call site hand-assembles or hand-parses it — the
 * DAO accessors in [com.vrcx.android.data.db.dao] and the friend-log repository
 * build keys through these helpers, and callers pass `(ownerId, entityId)`.
 */
internal fun accountScopedKey(ownerUserId: String, entityId: String): String = "$ownerUserId:$entityId"

/**
 * Inverse of [accountScopedKey]: recovers the entityId. Returns the whole string
 * unchanged when it contains no separator, matching the legacy hand-rolled
 * `substringAfter(':', original)` behavior.
 */
internal fun String.entityIdFromScopedKey(): String = substringAfter(':', this)
