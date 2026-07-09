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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(viewModel: ChartsViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val dailyActivity by viewModel.dailyActivity.collectAsStateWithLifecycle()
    val topWorlds by viewModel.topWorlds.collectAsStateWithLifecycle()
    val hourlyActivity by viewModel.hourlyActivity.collectAsStateWithLifecycle()
    val weekdayActivity by viewModel.weekdayActivity.collectAsStateWithLifecycle()
    val selectedRangeDays by viewModel.selectedRangeDays.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    val hasData = dailyActivity.isNotEmpty() ||
        topWorlds.isNotEmpty() ||
        hourlyActivity.any { it.second > 0 } ||
        weekdayActivity.any { it.second > 0 }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Charts", onBack = onBack)

        UiStateContainer(
            isLoading = isLoading,
            // Treat as fatal only when we have nothing to show AND an error occurred.
            // Never short-circuit on "no data" — the range filter and metric cards
            // must stay visible so the user can switch ranges even when empty.
            error = if (!hasData) error else null,
            isEmpty = false,
            onRetry = viewModel::refresh,
            emptyMessage = "No activity data yet",
            emptySubtitle = "Instance visit history will appear here as you use VRChat",
            emptyIcon = Icons.Outlined.Insights,
            modifier = Modifier.fillMaxSize(),
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(
                            7 to "7D",
                            30 to "30D",
                            90 to "90D",
                            null to "All",
                        ).forEachIndexed { index, (days, label) ->
                            SegmentedButton(
                                selected = selectedRangeDays == days,
                                onClick = { viewModel.setRangeDays(days) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                            ) {
                                Text(label)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChartsMetricCard("Visits", summary.totalVisits, Modifier.weight(1f))
                        ChartsMetricCard("Worlds", summary.distinctWorlds, Modifier.weight(1f))
                        ChartsMetricCard("Days", summary.activeDays, Modifier.weight(1f))
                    }

                    if (dailyActivity.isNotEmpty()) {
                        SectionHeader("Daily Instance Activity")
                        DailyActivityChart(dailyActivity)
                    }

                    if (topWorlds.isNotEmpty()) {
                        SectionHeader("Most Visited Worlds")
                        VrcxCard {
                            Column(Modifier.padding(16.dp)) {
                                val maxVisits = topWorlds.maxOf { it.second }.coerceAtLeast(1)
                                topWorlds.forEachIndexed { index, (name, count) ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${index + 1}.",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.width(24.dp),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth(fraction = count.toFloat() / maxVisits)
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text("$count", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    if (hourlyActivity.any { it.second > 0 }) {
                        SectionHeader("Activity by Hour")
                        BarChartCard(data = hourlyActivity, labelWidth = 56.dp)
                    }

                    if (weekdayActivity.any { it.second > 0 }) {
                        SectionHeader("Activity by Weekday")
                        BarChartCard(data = weekdayActivity, labelWidth = 56.dp)
                    }

                    if (!hasData && !isLoading) {
                        VrcxCard {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "No activity in this range",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "Try a wider range or come back once you've visited a few instances.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (error != null && hasData) {
                        Text(
                            "Refresh failed: $error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vertical bar chart for daily instance activity.
 *
 * Rolls our own renderer instead of using Vico because:
 *   - Vico renders a single data point as a giant rounded stadium that fills
 *     the chart area, which looks broken on days/ranges with little history.
 *   - We only need one chart type on this screen, so pulling in a chart
 *     library is overkill and keeps the UI language consistent with the
 *     horizontal [BarChartCard] used below.
 *
 * Bars use `primary` for days with activity; a thin `surfaceVariant` line
 * placeholder keeps empty days visible on the timeline so spacing reads
 * correctly. Width adapts to the bar count via [Modifier.weight].
 */
@Composable
private fun DailyActivityChart(data: List<Pair<String, Int>>) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${data.size} day${if (data.size == 1) "" else "s"} \u00B7 most recent ${data.last().first}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val maxCount = data.maxOf { it.second }.coerceAtLeast(1)
            val primary = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.surfaceVariant
            // Cap bar width so sparse ranges (1–few days of data) render as
            // recognizable columns instead of a single primary-colored slab.
            // When data is dense enough to fill the row, weight kicks in and
            // bars distribute evenly.
            val preferredBarWidth = 14.dp
            val barSpacing = 2.dp
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            ) {
                val totalPreferred = preferredBarWidth * data.size +
                    barSpacing * (data.size - 1).coerceAtLeast(0)
                val useFixedBarWidth = totalPreferred <= maxWidth
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(barSpacing, Alignment.End),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    data.forEach { (_, count) ->
                        val fraction = count.toFloat() / maxCount
                        val barModifier = if (useFixedBarWidth) {
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
                                        .fillMaxHeight(fraction.coerceAtLeast(0.04f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
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
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    data.first().first,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "peak $maxCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (data.size > 1) {
                    Text(
                        data.last().first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartsMetricCard(label: String, value: Int, modifier: Modifier = Modifier) {
    VrcxCard(modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BarChartCard(
    data: List<Pair<String, Int>>,
    labelWidth: Dp,
) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            val maxCount = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            data.forEach { (label, count) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
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
                                .fillMaxWidth(fraction = count.toFloat() / maxCount)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
    }
}
