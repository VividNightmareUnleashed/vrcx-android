package com.vrcx.android.data.model

/**
 * VRChat trust ranks, derived from a user's `system_trust_*` tags.
 *
 * [label] is persisted into the friend-log history, so the strings must stay
 * byte-identical — reword them and every friend spuriously logs a
 * "TrustLevel changed" row. [priority] drives sort order (lower = higher
 * trust) and keeps legend/veteran distinct even though they share a label.
 *
 * UI maps [label] to a color via `VrcxColors.trustColor`.
 */
enum class TrustRank(val label: String, val priority: Int) {
    LEGEND("Trusted User", 0),
    VETERAN("Trusted User", 1),
    TRUSTED("Known User", 2),
    KNOWN("User", 3),
    BASIC("New User", 4),
    VISITOR("Visitor", 5);

    companion object {
        fun fromTags(tags: List<String>): TrustRank = when {
            tags.contains("system_trust_legend") -> LEGEND
            tags.contains("system_trust_veteran") -> VETERAN
            tags.contains("system_trust_trusted") -> TRUSTED
            tags.contains("system_trust_known") -> KNOWN
            tags.contains("system_trust_basic") -> BASIC
            else -> VISITOR
        }
    }
}
