@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.domain.Analytics
import com.buildersledger.domain.TimeFormat
import com.buildersledger.domain.levelLabel
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.EmptyState
import com.buildersledger.ui.components.Fmt
import com.buildersledger.ui.components.SectionTitle
import com.buildersledger.ui.components.rememberNowMs

private const val DAY_MS = 86_400_000L

@Composable
fun InsightsScreen(vm: LedgerViewModel, padding: PaddingValues) {
    val village by vm.currentVillage.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val now by rememberNowMs(60_000L)
    var windowDays by remember { mutableIntStateOf(30) }

    if (village == null) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState("No village yet", "Add a village on the Board tab first.")
        }
        return
    }

    val start = now - windowDays * DAY_MS
    val utilization = remember(pools, history, active, windowDays, now / 60_000L) {
        Analytics.utilization(pools, history, active, start, now)
    }
    val spend = remember(history, active, windowDays, now / 60_000L) { Analytics.spend(history, active, start, now) }
    val finished = history.filter { !it.cancelled && it.endedAtMs in start..now }
    val averageSeconds = if (finished.isEmpty()) 0L else finished.sumOf { (it.endedAtMs - it.startedAtMs) / 1000L } / finished.size
    val poolNames = pools.associate { it.id to it.name }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "window") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { days ->
                    FilterChip(selected = windowDays == days, onClick = { windowDays = days }, label = { Text("$days days") })
                }
            }
        }

        item(key = "totals") {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${finished.size} upgrades finished", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (finished.isEmpty()) "Finished upgrades will show up here once you collect them."
                        else "Average length ${TimeFormat.duration(averageSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "util") {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How busy your workers were", style = MaterialTheme.typography.titleMedium)
                    utilization.forEach { u ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(u.pool.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${(u.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                            }
                            LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text(
                        "Counts time spent on tracked upgrades. Upgrades created by a sync count from the moment you synced, so the first days understate it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "spend") {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Spent on upgrades", style = MaterialTheme.typography.titleMedium)
                    if (spend.isEmpty()) {
                        Text(
                            "Add a cost when you start an upgrade to see spending here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        spend.forEach { (resource, amount) ->
                            Row {
                                Text(resource.label, modifier = Modifier.weight(1f))
                                Text(Fmt.amount(amount), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        item(key = "history-title") { SectionTitle("History") }
        if (history.isEmpty()) {
            item(key = "history-empty") {
                Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(history, key = { "h-${it.id}" }) { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, style = MaterialTheme.typography.bodyLarge)
                    val details = listOfNotNull(
                        levelLabel(h.fromLevel, h.toLevel),
                        poolNames[h.poolId],
                        if (h.cancelled) "cancelled" else null,
                        Fmt.whenText(h.endedAtMs),
                    ).joinToString(" · ")
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.deleteHistory(h.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove from history") }
            }
        }
    }
}
