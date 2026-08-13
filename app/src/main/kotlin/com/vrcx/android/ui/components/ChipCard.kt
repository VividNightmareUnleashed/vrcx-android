package com.vrcx.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A titled card of non-interactive chips — the Platforms and Tags cards on the
 * avatar and world detail screens.
 *
 * [labels] are rendered verbatim, so a caller that wants a suffix (the avatar
 * platform chips carry a performance rating) formats it in. Renders nothing when
 * [labels] is empty, so callers can drop it in without a guard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipCard(
    title: String,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    VrcxCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { label ->
                    AssistChip(
                        onClick = {},
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}
