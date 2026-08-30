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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar

private const val WEEK_RANGE_DAYS = 7
private const val MONTH_RANGE_DAYS = 30
private const val QUARTER_RANGE_DAYS = 90

private data class ChartRangeOption(val days: Int?, val label: String)

private val chartRangeOptions =
    listOf(
        ChartRangeOption(WEEK_RANGE_DAYS, "7D"),
        ChartRangeOption(MONTH_RANGE_DAYS, "30D"),
        ChartRangeOption(QUARTER_RANGE_DAYS, "90D"),
        ChartRangeOption(null, "All"),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(viewModel: ChartsViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedRangeDays by viewModel.selectedRangeDays.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Charts", onBack = onBack)
        ChartsBody(
            state = state,
            selectedRangeDays = selectedRangeDays,
            onSelectRange = viewModel::setRangeDays,
            onRefresh = viewModel::refresh,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartsBody(
    state: LoadState<ChartsData>,
    selectedRangeDays: Int?,
    onSelectRange: (Int?) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        LoadState.NotLoaded, LoadState.Loading -> LoadingState()

        is LoadState.Failed -> ErrorState(message = state.message, onRetry = onRefresh)

        is LoadState.Loaded ->
            LoadedCharts(
                loaded = state,
                selectedRangeDays = selectedRangeDays,
                onSelectRange = onSelectRange,
                onRefresh = onRefresh,
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedCharts(
    loaded: LoadState.Loaded<ChartsData>,
    selectedRangeDays: Int?,
    onSelectRange: (Int?) -> Unit,
    onRefresh: () -> Unit,
) {
    val charts = loaded.value
    PullToRefreshBox(
        isRefreshing = loaded.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChartRangeSelector(selectedRangeDays, onSelectRange)
            ChartsSummaryRow(charts.summary)
            if (charts.dailyActivity.isNotEmpty()) {
                SectionHeader("Daily Instance Activity")
                DailyActivityChart(charts.dailyActivity)
            }
            if (charts.topWorlds.isNotEmpty()) {
                SectionHeader("Most Visited Worlds")
                TopWorldsCard(charts.topWorlds)
            }
            if (charts.hourlyActivity.any { it.second > 0 }) {
                SectionHeader("Activity by Hour")
                BarChartCard(data = charts.hourlyActivity, labelWidth = 56.dp)
            }
            if (charts.weekdayActivity.any { it.second > 0 }) {
                SectionHeader("Activity by Weekday")
                BarChartCard(data = charts.weekdayActivity, labelWidth = 56.dp)
            }
            if (!charts.hasData) NoChartDataCard()
            loaded.staleError?.let { message ->
                Text(
                    text = "Refresh failed: $message",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ChartRangeSelector(selectedRangeDays: Int?, onSelectRange: (Int?) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        chartRangeOptions.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selectedRangeDays == option.days,
                onClick = { onSelectRange(option.days) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = chartRangeOptions.size,
                    ),
            ) {
                Text(option.label)
            }
        }
    }
}

@Composable
private fun ChartsSummaryRow(summary: ChartsSummary) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartsMetricCard("Visits", summary.totalVisits, Modifier.weight(1f))
        ChartsMetricCard("Worlds", summary.distinctWorlds, Modifier.weight(1f))
        ChartsMetricCard("Days", summary.activeDays, Modifier.weight(1f))
    }
}

@Composable
private fun TopWorldsCard(topWorlds: List<Pair<String, Int>>) {
    val maxVisits = topWorlds.maxOf { it.second }.coerceAtLeast(1)
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            topWorlds.forEachIndexed { index, (name, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
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
                                    .fillMaxWidth(count.toFloat() / maxVisits)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(count.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun NoChartDataCard() {
    VrcxCard {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("No activity in this range", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Try a wider range or come back once you've visited a few instances.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
