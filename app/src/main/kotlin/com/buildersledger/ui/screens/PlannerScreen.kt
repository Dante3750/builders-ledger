@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.data.isPlaceholderName
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.IdleGap
import com.buildersledger.domain.PlanEntry
import com.buildersledger.domain.PlanInput
import com.buildersledger.domain.PlanResult
import com.buildersledger.domain.Planner
import com.buildersledger.domain.Resource
import com.buildersledger.domain.ResourceState
import com.buildersledger.domain.TimeFormat
import com.buildersledger.domain.UnplannedReason
import com.buildersledger.domain.WishlistItem
import com.buildersledger.domain.WorkerPool
import com.buildersledger.domain.levelLabel
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.EmptyState
import com.buildersledger.ui.components.Fmt
import com.buildersledger.ui.components.SectionTitle
import com.buildersledger.ui.components.WishEditorDialog
import com.buildersledger.ui.components.rememberNowMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(vm: LedgerViewModel, padding: PaddingValues) {
    val village by vm.currentVillage.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val wishlist by vm.wishlist.collectAsStateWithLifecycle()
    val resources by vm.resources.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val patience by vm.patienceHours.collectAsStateWithLifecycle()
    val now by rememberNowMs(30_000L)

    val current = village
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState("No village yet", "Add a village on the Board tab first.")
        }
        return
    }

    val plan = remember(pools, active, wishlist, resources, patience, now) {
        Planner.plan(PlanInput(now, pools, active, wishlist, resources), patience * Planner.HOUR_MS)
    }
    val suggestions = remember(labels, history, active, wishlist) {
        (labels.values + history.map { it.name } + active.map { it.name } + wishlist.map { it.name })
            .filter { it.isNotBlank() && !isPlaceholderName(it) }
            .distinct()
            .sorted()
    }

    var editing by remember { mutableStateOf<WishlistItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val poolById = remember(pools) { pools.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") { PlanSummary(plan, resources, now) }

        if (wishlist.isNotEmpty() || active.isNotEmpty()) {
            item(key = "timeline") { Timeline(plan, pools, active, now) }
        }

        item(key = "wish-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Wishlist", Modifier.weight(1f))
                Button(onClick = { adding = true }, enabled = pools.isNotEmpty()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Plan upgrade")
                }
            }
        }
        if (wishlist.isEmpty()) {
            item(key = "wish-empty") {
                Text(
                    "Add the upgrades you want next, with their time and cost from the game. The planner orders them so no worker sits idle and nothing starts before you can pay for it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(wishlist, key = { "wish-${it.id}" }) { item ->
            WishCard(
                item = item,
                poolName = poolById[item.poolId]?.name ?: "?",
                onEdit = { editing = item },
                onDelete = { vm.deleteWish(item.id) },
            )
        }

        if (plan.entries.isNotEmpty()) {
            item(key = "order-header") { SectionTitle("Order of play") }
            items(plan.entries, key = { "plan-${it.item.id}" }) { entry ->
                PlanRow(entry, poolById[entry.poolId], now)
            }
        }
        if (plan.unplanned.isNotEmpty()) {
            item(key = "unplanned-header") { SectionTitle("Could not be planned") }
            items(plan.unplanned, key = { "unplanned-${it.item.id}" }) { u ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(u.item.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            when (u.reason) {
                                UnplannedReason.NO_WORKER_POOL -> "Its worker pool no longer exists. Edit it and pick another."
                                UnplannedReason.NO_SLOTS -> "That worker pool has no slots. Set its count in Settings."
                                UnplannedReason.NEVER_AFFORDABLE ->
                                    "You will not have enough ${u.item.costResource?.label ?: "resources"} for this. Enter an income per hour on the Resources tab, or lower the cost."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        WishEditorDialog(
            initial = editing,
            villageId = current.id,
            pools = pools,
            suggestions = suggestions,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = {
                vm.saveWish(it)
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun PlanSummary(plan: PlanResult, resources: Map<Resource, ResourceState>, now: Long) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val completion = plan.completionMs
            if (completion == null) {
                Text("Nothing planned yet", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Plan finishes ${Fmt.whenText(completion)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "That is ${Fmt.countdown(completion - now)} from now, across ${plan.entries.size} upgrade${if (plan.entries.size == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (plan.idleGaps.isNotEmpty()) {
                val idleMs = plan.idleGaps.sumOf { it.toMs - it.fromMs }
                Text(
                    "Workers wait ${Fmt.countdown(idleMs)} in total for resources. More income or a cheaper order would close that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for ((resource, needed) in plan.totalCost) {
                val have = resources[resource]?.amountAt(now) ?: 0L
                val short = needed - have
                Text(
                    text = "${resource.label}: ${Fmt.compact(needed)} needed, ${Fmt.compact(have)} now" +
                        if (short > 0) " (short by ${Fmt.compact(short)})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (short > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private val RunningColor = Color(0xFFB36B00)

/**
 * A simple Gantt chart: one row per worker slot, time runs left to right from now.
 * Built from ordinary composables (no Canvas) so labels, theming and accessibility come for free.
 */
@Composable
private fun Timeline(plan: PlanResult, pools: List<WorkerPool>, active: List<ActiveUpgrade>, now: Long) {
    val rows = remember(pools, active) {
        buildList<TimelineRow> {
            for (pool in pools) {
                val running = active.filter { it.poolId == pool.id }.sortedBy { it.endsAtMs }
                val slots = maxOf(pool.slots, running.size)
                for (s in 0 until slots) {
                    val label = if (slots == 1) pool.name else "${pool.name} ${s + 1}"
                    add(TimelineRow(pool.id, s, label, running.getOrNull(s)))
                }
            }
        }
    }
    if (rows.isEmpty()) return

    val lastEnd = maxOf(
        plan.entries.maxOfOrNull { it.endMs } ?: now,
        active.maxOfOrNull { it.endsAtMs } ?: now,
        now + Planner.HOUR_MS,
    )
    val horizon = (lastEnd - now).coerceAtMost(60L * 24L * Planner.HOUR_MS).toFloat()

    fun frac(ms: Long): Float = ((ms - now).coerceIn(0L, horizon.toLong()).toFloat() / horizon)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleMedium)
            for (row in rows) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(76.dp),
                    )
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        val total = maxWidth
                        row.running?.let { r ->
                            Bar(total, 0f, frac(r.endsAtMs), r.name, RunningColor, Color.White)
                        }
                        plan.idleGaps.filter { it.poolId == row.poolId && it.slotIndex == row.slot }.forEach { gap: IdleGap ->
                            Bar(total, frac(gap.fromMs), frac(gap.toMs), "", MaterialTheme.colorScheme.error.copy(alpha = 0.25f), Color.Unspecified)
                        }
                        plan.entries.filter { it.poolId == row.poolId && it.slotIndex == row.slot }.forEach { e: PlanEntry ->
                            Bar(
                                total, frac(e.startMs), frac(e.endMs), e.item.name,
                                MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Now", style = MaterialTheme.typography.labelSmall)
                Text(Fmt.whenText(now + horizon.toLong()), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(RunningColor, "Running")
                LegendDot(MaterialTheme.colorScheme.primaryContainer, "Planned")
                LegendDot(MaterialTheme.colorScheme.error.copy(alpha = 0.25f), "Waiting for resources")
            }
        }
    }
}

private data class TimelineRow(val poolId: Long, val slot: Int, val label: String, val running: ActiveUpgrade?)

@Composable
private fun Bar(
    total: androidx.compose.ui.unit.Dp,
    startFraction: Float,
    endFraction: Float,
    label: String,
    color: Color,
    textColor: Color,
) {
    val width = maxOf(total * (endFraction - startFraction).coerceAtLeast(0f), 3.dp)
    Box(
        Modifier
            .offset(x = total * startFraction)
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(5.dp))
            .background(color),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WishCard(item: WishlistItem, poolName: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                val cost = if (item.costAmount > 0 && item.costResource != null) {
                    "${Fmt.compact(item.costAmount)} ${item.costResource!!.label}"
                } else null
                val details = listOfNotNull(
                    levelLabel(item.fromLevel, item.toLevel),
                    TimeFormat.duration(item.durationSeconds),
                    cost,
                    poolName,
                    "priority ${item.priority}",
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun PlanRow(entry: PlanEntry, pool: WorkerPool?, now: Long) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(112.dp)) {
            Text(
                if (entry.startMs <= now) "Start now" else Fmt.whenText(entry.startMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(pool?.name ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.item.name, style = MaterialTheme.typography.bodyLarge)
            val tail = buildString {
                append("ends ").append(Fmt.whenText(entry.endMs))
                if (entry.waitedMs > 0) append(" · worker idles ").append(Fmt.countdown(entry.waitedMs)).append(" first")
            }
            Text(
                tail,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.waitedMs > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
