package com.vrcx.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.theme.TrustFriend
import com.vrcx.android.ui.theme.TrustKnownUser
import com.vrcx.android.ui.theme.TrustNewUser
import com.vrcx.android.ui.theme.TrustTrustedUser
import com.vrcx.android.ui.theme.TrustUser
import com.vrcx.android.ui.theme.TrustVisitor

fun trustLevelFromTags(tags: List<String>): Pair<String, Color> {
    return when {
        tags.contains("system_trust_legend") || tags.contains("system_trust_veteran") ->
            "Trusted User" to TrustTrustedUser
        tags.contains("system_trust_trusted") ->
            "Known User" to TrustKnownUser
        tags.contains("system_trust_known") ->
            "User" to TrustUser
        tags.contains("system_trust_basic") ->
            "New User" to TrustNewUser
        else -> "Visitor" to TrustVisitor
    }
}

@Composable
fun TrustRankBadge(tags: List<String>, modifier: Modifier = Modifier) {
    val (label, color) = trustLevelFromTags(tags)
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
