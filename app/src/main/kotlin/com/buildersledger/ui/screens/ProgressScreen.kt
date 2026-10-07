@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.domain.ApiVillage
import com.buildersledger.domain.ItemProgress
import com.buildersledger.domain.Remaining
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.Fmt
import com.buildersledger.ui.components.SectionTitle

@Composable
fun ProgressScreen(vm: LedgerViewModel, padding: PaddingValues, onOpenSettings: () -> Unit) {
    val cached by vm.progress.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val error by vm.syncError.collectAsStateWithLifecycle()
    val key by vm.apiKey.collectAsStateWithLifecycle()
    var village by remember { mutableStateOf(ApiVillage.HOME) }

    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = { vm.syncProgress() },
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "sync") {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Progress from official API", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Optional. Shows troop, spell, hero, equipment and siege levels against their maximum. " +
                                "The API does not include buildings, upgrade timers, resources or builder counts, so the Board and Planner still use the export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val c = cached
                        Text(
                            if (c != null) "Last synced ${Fmt.whenText(c.syncedAtMs)}" else "Not synced yet",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { vm.syncProgress() }, enabled = !syncing && key.isNotBlank()) {
                                Text(if (syncing) "Syncing…" else "Sync now")
                            }
                            OutlinedButton(onClick = onOpenSettings) { Text("API settings") }
                        }
                        if (key.isBlank()) {
                            Text(
                                "No API key yet. Add your player tag and key in Settings to turn this on.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            error?.let { err ->
                item(key = "error") {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Sync failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(err.message, style = MaterialTheme.typography.bodyMedium)
                            if (cached != null) {
                                Text(
                                    "Showing the last successful sync below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            val c = cached
            if (c != null) {
                val p = c.progress
                item(key = "who") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.name ?: "Player", style = MaterialTheme.typography.titleLarge)
                        Text(
                            listOfNotNull(
                                p.tag,
                                p.townHall?.let { "Town Hall $it" },
                                p.builderHall?.let { "Builder Hall $it" },
                                p.clanName,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Maximums are for the player's current Town Hall / Builder Hall, as reported by the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "filter") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiVillage.entries.forEach { v ->
                            FilterChip(selected = village == v, onClick = { village = v }, label = { Text(v.label) })
                        }
                    }
                }
                val overall = p.remaining(village)
                if (overall.total == 0) {
                    item(key = "none") {
                        Text(
                            "The response had no levelled items with a known maximum for ${village.label.lowercase()}.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item(key = "overall") {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Overall to max: ${overall.percent}%", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(progress = { overall.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text(summary(overall), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "kinds-title") { SectionTitle("By category") }
                    items(p.byKind(village).entries.toList(), key = { "kind-${it.key.name}" }) { (kind, rem) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                                Text("${rem.percent}%", style = MaterialTheme.typography.bodyLarge)
                            }
                            LinearProgressIndicator(progress = { rem.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text(summary(rem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val closest = p.closestToMax(village, 10)
                    item(key = "closest-title") { SectionTitle("Closest to max") }
                    if (closest.isEmpty()) {
                        item(key = "closest-none") { Text("Everything here is maxed.", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(closest, key = { "c-${it.kind.name}-${it.name}" }) { ClosestRow(it) }
                    }
                }
            } else if (error == null && key.isNotBlank()) {
                item(key = "hint") {
                    Text("Pull down or tap Sync now. Nothing has been fetched yet.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun summary(r: Remaining): String =
    "${r.maxed} of ${r.total} maxed · ${r.levelsLeft} level${if (r.levelsLeft == 1) "" else "s"} left"

@Composable
private fun ClosestRow(item: ItemProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text("${item.level} / ${item.maxLevel}", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(progress = { item.level.toFloat() / item.maxLevel }, modifier = Modifier.fillMaxWidth())
        Text(
            "${item.kind.label} · ${item.levelsLeft} level${if (item.levelsLeft == 1) "" else "s"} left",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
