@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.data.isPlaceholderName
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.CapWarning
import com.buildersledger.domain.PlanEntry
import com.buildersledger.domain.PlanInput
import com.buildersledger.domain.Planner
import com.buildersledger.domain.TimeFormat
import com.buildersledger.domain.Village
import com.buildersledger.domain.WorkerPool
import com.buildersledger.domain.levelLabel
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.ActiveEditorDialog
import com.buildersledger.ui.components.EmptyState
import com.buildersledger.ui.components.Fmt
import com.buildersledger.ui.components.rememberNowMs

private data class EditorRequest(val existing: ActiveUpgrade?, val poolId: Long?)

@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    padding: PaddingValues,
    onCreateVillage: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val villages by vm.villages.collectAsStateWithLifecycle()
    val village by vm.currentVillage.collectAsStateWithLifecycle()

    when {
        villages == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        village == null -> Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState(
                title = "Welcome, builder",
                body = "Add your village to start tracking what every builder, lab and helper is doing.",
            ) { Button(onClick = onCreateVillage) { Text("Add my village") } }
        }
        else -> HomeContent(vm, village!!, padding, onOpenImport)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(vm: LedgerViewModel, village: Village, padding: PaddingValues, onOpenImport: () -> Unit) {
    val pools by vm.pools.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val wishlist by vm.wishlist.collectAsStateWithLifecycle()
    val resources by vm.resources.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val patience by vm.patienceHours.collectAsStateWithLifecycle()
    val onboardingDone by vm.onboardingDone.collectAsStateWithLifecycle()

    val now by rememberNowMs(1000L)

    // The plan only needs refreshing about once a minute; countdowns tick every second.
    val minuteBucket = now / 60_000L
    val plan = remember(pools, active, wishlist, resources, patience, minuteBucket) {
        Planner.plan(PlanInput(now, pools, active, wishlist, resources), patience * Planner.HOUR_MS)
    }

    val suggestions = remember(labels, history, active, wishlist) {
        (labels.values + history.map { it.name } + active.map { it.name } + wishlist.map { it.name })
            .filter { it.isNotBlank() && !isPlaceholderName(it) }
            .distinct()
            .sorted()
    }

    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var cancelTarget by remember { mutableStateOf<ActiveUpgrade?>(null) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                SummaryCard(
                    pools = pools,
                    active = active,
                    now = now,
                    onCollectAll = { vm.collectAllDone() },
                    onImport = onOpenImport,
                )
            }
            if (onboardingDone == false) {
                item(key = "onboarding") {
                    OnboardingCard(onOpenImport = onOpenImport, onDismiss = { vm.dismissOnboarding() })
                }
            }
            if (plan.capWarnings.isNotEmpty()) {
                item(key = "cap-warnings") { CapWarningsCard(plan.capWarnings.take(3), now) }
            }
            if (pools.isEmpty()) {
                item(key = "nopools") {
                    Text(
                        "This village has no worker pools yet. Add some in Settings, for example Builders and Laboratory.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            for (pool in pools) {
                item(key = "pool-${pool.id}") {
                    PoolSection(
                        pool = pool,
                        running = active.filter { it.poolId == pool.id }.sortedBy { it.endsAtMs },
                        suggestions = plan.startNow(now).filter { it.poolId == pool.id },
                        now = now,
                        onEdit = { editor = EditorRequest(it, null) },
                        onCollect = { vm.finishActive(it, cancelled = false) },
                        onCancel = { cancelTarget = it },
                        onStartPlanned = { vm.startFromWishlist(it.item) },
                        onAdd = { editor = EditorRequest(null, pool.id) },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { editor = EditorRequest(null, null) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Start upgrade") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editor?.let { request ->
        ActiveEditorDialog(
            initial = request.existing,
            villageId = village.id,
            pools = pools,
            suggestions = suggestions,
            defaultPoolId = request.poolId,
            onDismiss = { editor = null },
            onSave = { upgrade, pay ->
                vm.saveActive(upgrade, pay)
                editor = null
            },
        )
    }

    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("Cancel this upgrade?") },
            text = { Text("${target.name} will be moved to history as cancelled. Remember to cancel it in the game too.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.finishActive(target, cancelled = true)
                    cancelTarget = null
                }) { Text("Cancel upgrade") }
            },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun SummaryCard(
    pools: List<WorkerPool>,
    active: List<ActiveUpgrade>,
    now: Long,
    onCollectAll: () -> Unit,
    onImport: () -> Unit,
) {
    val ready = active.count { it.isDone(now) }
    val working = active.size - ready
    val totalSlots = pools.sumOf { pool -> maxOf(pool.slots, active.count { it.poolId == pool.id }) }
    val idle = (totalSlots - active.size).coerceAtLeast(0)
    val nextFree = active.filter { !it.isDone(now) }.minOfOrNull { it.endsAtMs }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$working working · $ready ready to collect · $idle idle", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    nextFree != null -> "Next worker is free in ${Fmt.countdown(nextFree - now)} (${Fmt.whenText(nextFree)})"
                    ready > 0 -> "Everything has finished."
                    else -> "Nothing is running. Start an upgrade or sync from your village export."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready > 0) Button(onClick = onCollectAll) { Text("Collect all ($ready)") }
                OutlinedButton(onClick = onImport) { Text("Sync from export") }
            }
        }
    }
}

@Composable
private fun PoolSection(
    pool: WorkerPool,
    running: List<ActiveUpgrade>,
    suggestions: List<PlanEntry>,
    now: Long,
    onEdit: (ActiveUpgrade) -> Unit,
    onCollect: (ActiveUpgrade) -> Unit,
    onCancel: (ActiveUpgrade) -> Unit,
    onStartPlanned: (PlanEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val slots = maxOf(pool.slots, running.size)
    val busy = running.count { !it.isDone(now) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pool.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$busy / $slots busy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAdd) { Text("+ Start") }
        }
        for (a in running) {
            UpgradeCard(a, now, onEdit = { onEdit(a) }, onCollect = { onCollect(a) }, onCancel = { onCancel(a) })
        }
        val idleCount = (slots - running.size).coerceAtLeast(0)
        for (i in 0 until idleCount) {
            IdleCard(suggestion = suggestions.getOrNull(i), onStart = onStartPlanned, onAdd = onAdd)
        }
    }
}

@Composable
private fun UpgradeCard(
    a: ActiveUpgrade,
    now: Long,
    onEdit: () -> Unit,
    onCollect: () -> Unit,
    onCancel: () -> Unit,
) {
    val done = a.isDone(now)
    val placeholder = isPlaceholderName(a.name)
    ElevatedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (placeholder) "Tap to name this upgrade" else a.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (placeholder) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    )
                    val sub = listOfNotNull(levelLabel(a.fromLevel, a.toLevel), if (placeholder) a.name.removePrefix("Unnamed ") else null)
                        .joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text = if (done) "Done" else Fmt.countdown(a.remainingMs(now)),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(progress = { a.progress(now) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cost = if (a.costAmount > 0 && a.costResource != null) " · ${Fmt.compact(a.costAmount)} ${a.costResource!!.label}" else ""
                Text(
                    text = (if (done) "Finished ${Fmt.whenText(a.endsAtMs)}" else "Ends ${Fmt.whenText(a.endsAtMs)}") + cost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (done) {
                    Button(onClick = onCollect) { Text("Collect") }
                } else {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun IdleCard(suggestion: PlanEntry?, onStart: (PlanEntry) -> Unit, onAdd: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Idle", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                if (suggestion != null) {
                    val item = suggestion.item
                    val details = listOfNotNull(levelLabel(item.fromLevel, item.toLevel), TimeFormat.duration(item.durationSeconds))
                        .joinToString(" · ")
                    Text("Next in your plan: ${item.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "Nothing planned for this slot",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (suggestion != null) {
                Button(onClick = { onStart(suggestion) }) { Text("Start") }
            } else {
                TextButton(onClick = onAdd) { Text("Add") }
            }
        }
    }
}

@Composable
private fun OnboardingCard(onOpenImport: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Start here: sync from export",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "The game can copy your village as text. In the game open Settings, then More Settings, then Data Export, " +
                    "and copy it. Then tap Sync from export here and paste it. Builder's Ledger reads the running timers, " +
                    "fills your board and sets reminders. Sync again whenever you like: it updates instead of duplicating. " +
                    "Everything stays on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onDismiss()
                    onOpenImport()
                }) { Text("Sync from export") }
                TextButton(onClick = onDismiss) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun CapWarningsCard(warnings: List<CapWarning>, now: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in warnings) {
                val whenText = if (w.reachedAtMs <= now) "is full" else "cap reached at ${Fmt.whenText(w.reachedAtMs)}"
                Text(
                    "${w.resource.label} $whenText: spend or lose income",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                if (w.lostAmount > 0L) {
                    Text(
                        "About ${Fmt.compact(w.lostAmount)} would be lost before your next planned spend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
