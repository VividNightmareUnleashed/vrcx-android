package com.vrcx.android.ui.screen.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.components.VrcxCard

private const val MIN_VISIBLE_BAR_FRACTION = 0.04f

/**
 * A narrow custom chart avoids the oversized single-value rendering produced by
 * the general chart library and keeps sparse and dense histories on one scale.
 */
@Composable
internal fun DailyActivityChart(data: List<Pair<String, Int>>) {
    val maxCount = data.maxOf { it.second }.coerceAtLeast(1)
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text =
                    "${data.size} day${if (data.size == 1) "" else "s"} " +
                        "\u00B7 most recent ${data.last().first}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            DailyActivityBars(data, maxCount)
            Spacer(Modifier.height(6.dp))
            DailyActivityLabels(data, maxCount)
        }
    }
}

@Composable
private fun DailyActivityBars(data: List<Pair<String, Int>>, maxCount: Int) {
    val preferredBarWidth = 14.dp
    val barSpacing = 2.dp
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(Modifier.fillMaxWidth().height(160.dp)) {
        val totalPreferred =
            preferredBarWidth * data.size + barSpacing * (data.size - 1).coerceAtLeast(0)
        val useFixedBarWidth = totalPreferred <= maxWidth
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(barSpacing, Alignment.End),
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEach { (_, count) ->
                val barModifier =
                    if (useFixedBarWidth) {
                        Modifier.width(preferredBarWidth).fillMaxHeight()
                    } else {
                        Modifier.weight(1f).fillMaxHeight()
                    }
                Column(
                    barModifier,
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (count > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(
                                    (count.toFloat() / maxCount)
                                        .coerceAtLeast(MIN_VISIBLE_BAR_FRACTION),
                                ).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(primary),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(track),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyActivityLabels(data: List<Pair<String, Int>>, maxCount: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ChartLabel(data.first().first)
        ChartLabel("peak $maxCount")
        if (data.size > 1) ChartLabel(data.last().first)
    }
}

@Composable
private fun ChartLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ChartsMetricCard(label: String, value: Int, modifier: Modifier = Modifier) {
    VrcxCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun BarChartCard(data: List<Pair<String, Int>>, labelWidth: Dp) {
    val maxCount = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            data.forEach { (label, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(labelWidth),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(count.toFloat() / maxCount)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
    }
}
