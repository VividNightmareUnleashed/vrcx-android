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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Charts", onBack = onBack)

        if (isLoading) {
            LoadingState()
        } else if (dailyActivity.isEmpty() && topWorlds.isEmpty()) {
            EmptyState(message = "No activity data yet", subtitle = "Instance visit history will appear here as you use VRChat")
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Daily Activity
                if (dailyActivity.isNotEmpty()) {
                    SectionHeader("Daily Instance Activity")
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            val maxCount = dailyActivity.maxOf { it.second }.coerceAtLeast(1)
                            dailyActivity.forEach { (date, count) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(80.dp))
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
                                    Text("$count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
                                }
                            }
                        }
                    }
                }

                // Top Worlds
                if (topWorlds.isNotEmpty()) {
                    SectionHeader("Most Visited Worlds")
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            val maxVisits = topWorlds.maxOf { it.second }.coerceAtLeast(1)
                            topWorlds.forEachIndexed { index, (name, count) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
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
            }
        }
    }
}
