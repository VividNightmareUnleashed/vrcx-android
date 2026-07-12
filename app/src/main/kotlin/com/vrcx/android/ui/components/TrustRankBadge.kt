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
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.model.TrustRank
import com.vrcx.android.ui.theme.vrcxColors

@Composable
fun TrustRankBadge(tags: List<String>, modifier: Modifier = Modifier) {
    val rank = TrustRank.fromTags(tags)
    val color = MaterialTheme.vrcxColors.trustColor(rank.label)
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = rank.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
