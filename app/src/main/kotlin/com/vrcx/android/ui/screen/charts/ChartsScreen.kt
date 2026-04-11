package com.vrcx.android.ui.screen.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(viewModel: ChartsViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val isLoading by viewModel.isLoading.collectAsState()
    val dailyActivity by viewModel.dailyActivity.collectAsState()
    val topWorlds by viewModel.topWorlds.collectAsState()
    val hourlyActivity by viewModel.hourlyActivity.collectAsState()
    val weekdayActivity by viewModel.weekdayActivity.collectAsState()
    val selectedRangeDays by viewModel.selectedRangeDays.collectAsState()
    val summary by viewModel.summary.collectAsState()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Charts", onBack = onBack)

        if (isLoading) {
            LoadingState()
        } else if (
            dailyActivity.isEmpty() &&
            topWorlds.isEmpty() &&
            hourlyActivity.all { it.second == 0 } &&
            weekdayActivity.all { it.second == 0 }
        ) {
            EmptyState(
                message = "No activity data yet",
                subtitle = "Instance visit history will appear here as you use VRChat",
            )
        } else {
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
                    BarChartCard(data = dailyActivity, labelWidth = 80.dp)
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
                                        Box(
                                            Modifier
                                                .fillMaxWidth(fraction = count.toFloat() / maxVisits)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
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
