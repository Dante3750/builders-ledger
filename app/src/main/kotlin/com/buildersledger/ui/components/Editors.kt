@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buildersledger.data.isPlaceholderName
import com.buildersledger.domain.ActiveUpgrade
import com.buildersledger.domain.Resource
import com.buildersledger.domain.TimeFormat
import com.buildersledger.domain.Village
import com.buildersledger.domain.WishlistItem
import com.buildersledger.domain.WorkerPool

/** Everything the upgrade form edits, kept as text so half-typed input is never rejected. */
@Stable
class UpgradeFormState(
    name: String = "",
    from: String = "",
    to: String = "",
    time: String = "",
    cost: String = "",
    resource: Resource = Resource.GOLD,
    poolId: Long? = null,
    priority: Int = 3,
    deadline: String = "",
) {
    var name by mutableStateOf(name)
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var time by mutableStateOf(time)
    var cost by mutableStateOf(cost)
    var resource by mutableStateOf(resource)
    var poolId by mutableStateOf(poolId)
    var priority by mutableStateOf(priority)
    var deadline by mutableStateOf(deadline)

    val seconds: Long? get() = TimeFormat.parse(time)
    val costAmount: Long get() = cost.toLongOrNull() ?: 0L

    /** Blank = no deadline. Otherwise the parsed "finish within" span in seconds, or null when unreadable. */
    val deadlineSeconds: Long? get() = if (deadline.isBlank()) null else TimeFormat.parse(deadline)
    val deadlineOk: Boolean get() = deadline.isBlank() || TimeFormat.parse(deadline) != null
}

@Composable
private fun UpgradeForm(
    state: UpgradeFormState,
    pools: List<WorkerPool>,
    suggestions: List<String>,
    nameHint: String?,
    timeLabel: String,
    showPriority: Boolean,
    showDeadline: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            label = { Text("Name") },
            singleLine = true,
            supportingText = if (nameHint != null) ({ Text(nameHint) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        SuggestionRow(state.name, suggestions) { state.name = it }

        DropdownField(
            label = "Worker",
            options = pools,
            selected = pools.firstOrNull { it.id == state.poolId },
            optionLabel = { it.name },
            onSelected = { state.poolId = it.id },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(state.from, { state.from = it }, "From level", Modifier.weight(1f))
            NumberField(state.to, { state.to = it }, "To level", Modifier.weight(1f))
        }

        DurationField(state.time, { state.time = it }, timeLabel, Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            NumberField(state.cost, { state.cost = it }, "Cost (optional)", Modifier.weight(1f))
            ResourcePicker(state.resource, { state.resource = it }, Modifier.weight(1f))
        }

        if (showPriority) PriorityPicker(state.priority) { state.priority = it }

        if (showDeadline) {
            val parsed = state.deadlineSeconds
            OutlinedTextField(
                value = state.deadline,
                onValueChange = { state.deadline = it },
                label = { Text("Finish within (optional)") },
                singleLine = true,
                isError = !state.deadlineOk,
                supportingText = {
                    Text(
                        when {
                            state.deadline.isBlank() -> "A deadline, counted from now, e.g. 5d. The planner warns if it cannot be met."
                            parsed == null -> "Use d, h, m and s, for example 5d 12h"
                            else -> "= done by ${Fmt.whenText(System.currentTimeMillis() + parsed * 1000L)}"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Start a new running upgrade, or correct one that is already running. */
@Composable
fun ActiveEditorDialog(
    initial: ActiveUpgrade?,
    villageId: Long,
    pools: List<WorkerPool>,
    suggestions: List<String>,
    defaultPoolId: Long?,
    onDismiss: () -> Unit,
    onSave: (ActiveUpgrade, Boolean) -> Unit,
) {
    val nowAtOpen = remember { System.currentTimeMillis() }
    val initialTime = remember(initial) {
        initial?.let { TimeFormat.duration(it.remainingMs(nowAtOpen) / 1000L) } ?: ""
    }
    val state = remember(initial) {
        UpgradeFormState(
            name = initial?.name?.takeUnless { isPlaceholderName(it) } ?: "",
            from = initial?.fromLevel?.toString() ?: "",
            to = initial?.toLevel?.toString() ?: "",
            time = initialTime,
            cost = initial?.costAmount?.takeIf { it > 0 }?.toString() ?: "",
            resource = initial?.costResource ?: Resource.GOLD,
            poolId = initial?.poolId ?: defaultPoolId ?: pools.firstOrNull()?.id,
        )
    }
    var payFromBalance by remember { mutableStateOf(true) }

    val needsName = initial != null && isPlaceholderName(initial.name)
    val nameOk = state.name.isNotBlank() || (initial != null && !needsName)
    val canSave = nameOk && state.seconds != null && state.poolId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Start an upgrade" else "Edit upgrade") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UpgradeForm(
                    state = state,
                    pools = pools,
                    suggestions = suggestions,
                    nameHint = if (needsName) "Name it once and future imports will remember it" else null,
                    timeLabel = "Time left",
                    showPriority = false,
                )
                if (initial == null && state.costAmount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = payFromBalance, onCheckedChange = { payFromBalance = it })
                        Text("Subtract the cost from my balance", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val now = System.currentTimeMillis()
                    val seconds = state.seconds ?: return@TextButton
                    val poolId = state.poolId ?: return@TextButton
                    val untouched = initial != null && state.time == initialTime
                    val endsAt = if (untouched) initial!!.endsAtMs else now + seconds * 1000L
                    val amount = state.costAmount
                    onSave(
                        ActiveUpgrade(
                            id = initial?.id ?: 0L,
                            villageId = villageId,
                            poolId = poolId,
                            name = state.name.trim().ifBlank { initial?.name ?: "" },
                            fromLevel = state.from.toIntOrNull(),
                            toLevel = state.to.toIntOrNull(),
                            startedAtMs = initial?.startedAtMs ?: now,
                            endsAtMs = endsAt,
                            costAmount = amount,
                            costResource = if (amount > 0) state.resource else null,
                            sourceKey = initial?.sourceKey,
                            note = initial?.note ?: "",
                        ),
                        initial == null && payFromBalance,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add or edit something on the wishlist. */
@Composable
fun WishEditorDialog(
    initial: WishlistItem?,
    villageId: Long,
    pools: List<WorkerPool>,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (WishlistItem) -> Unit,
) {
    val nowAtOpen = remember { System.currentTimeMillis() }
    val initialDeadline = remember(initial) {
        initial?.finishByMs?.let { TimeFormat.duration(((it - nowAtOpen) / 1000L).coerceAtLeast(0L)) } ?: ""
    }
    val state = remember(initial) {
        UpgradeFormState(
            name = initial?.name ?: "",
            from = initial?.fromLevel?.toString() ?: "",
            to = initial?.toLevel?.toString() ?: "",
            time = initial?.let { TimeFormat.duration(it.durationSeconds) } ?: "",
            cost = initial?.costAmount?.takeIf { it > 0 }?.toString() ?: "",
            resource = initial?.costResource ?: Resource.GOLD,
            poolId = initial?.poolId ?: pools.firstOrNull()?.id,
            priority = initial?.priority ?: 3,
            deadline = initialDeadline,
        )
    }
    val canSave = state.name.isNotBlank() && state.seconds != null && state.poolId != null && state.deadlineOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Plan an upgrade" else "Edit plan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                UpgradeForm(
                    state = state,
                    pools = pools,
                    suggestions = suggestions,
                    nameHint = null,
                    timeLabel = "How long it takes",
                    showPriority = true,
                    showDeadline = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val seconds = state.seconds ?: return@TextButton
                    val poolId = state.poolId ?: return@TextButton
                    val amount = state.costAmount
                    val untouchedDeadline = initial != null && state.deadline == initialDeadline
                    val finishBy = when {
                        untouchedDeadline -> initial!!.finishByMs
                        else -> state.deadlineSeconds?.let { System.currentTimeMillis() + it * 1000L }
                    }
                    onSave(
                        WishlistItem(
                            id = initial?.id ?: 0L,
                            villageId = villageId,
                            poolId = poolId,
                            name = state.name.trim(),
                            fromLevel = state.from.toIntOrNull(),
                            toLevel = state.to.toIntOrNull(),
                            durationSeconds = seconds,
                            costAmount = amount,
                            costResource = if (amount > 0) state.resource else null,
                            priority = state.priority,
                            finishByMs = finishBy,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Create a village (with builder count) or edit an existing one. */
@Composable
fun VillageDialog(
    initial: Village?,
    onDismiss: () -> Unit,
    onSave: (name: String, townHall: Int, builders: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var townHall by remember { mutableStateOf(initial?.townHall?.toString() ?: "") }
    var builders by remember { mutableStateOf("5") }
    val th = townHall.toIntOrNull()
    val canSave = name.isNotBlank() && th != null && th in 1..30

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New village" else "Edit village") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(townHall, { townHall = it }, "Town Hall level", Modifier.fillMaxWidth())
                if (initial == null) {
                    NumberField(
                        builders,
                        { builders = it },
                        "Builders",
                        Modifier.fillMaxWidth(),
                        supporting = "You can change worker counts later in Settings",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onSave(name.trim(), th ?: 1, builders.toIntOrNull() ?: 5) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
