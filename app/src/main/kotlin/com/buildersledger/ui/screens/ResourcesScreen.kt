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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.domain.Resource
import com.buildersledger.domain.ResourceState
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.EmptyState
import com.buildersledger.ui.components.Fmt
import com.buildersledger.ui.components.NumberField
import com.buildersledger.ui.components.rememberNowMs

@Composable
fun ResourcesScreen(vm: LedgerViewModel, padding: PaddingValues) {
    val village by vm.currentVillage.collectAsStateWithLifecycle()
    val resources by vm.resources.collectAsStateWithLifecycle()
    val wishlist by vm.wishlist.collectAsStateWithLifecycle()
    val now by rememberNowMs(30_000L)

    if (village == null) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState("No village yet", "Add a village on the Board tab first.")
        }
        return
    }

    val amountEdits = remember(village?.id) { mutableStateMapOf<Resource, String>() }
    val incomeEdits = remember(village?.id) { mutableStateMapOf<Resource, String>() }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Text(
                "Enter what you have and how much comes in per hour (collectors, mines, drills, loot you expect). " +
                    "The planner adds income to your balance as time passes, so you do not need to re-enter it every visit. " +
                    "Update it whenever you check the game for the most accurate plan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(Resource.entries.toList(), key = { it.name }) { resource ->
            val state = resources[resource]
            val wanted = wishlist.filter { it.costResource == resource }.sumOf { it.costAmount }
            val haveNow = state?.amountAt(now) ?: 0L
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resource.label, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        NumberField(
                            value = amountEdits[resource] ?: haveNow.toString(),
                            onValueChange = {
                                amountEdits[resource] = it
                                saved = false
                            },
                            label = "Have now",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = incomeEdits[resource] ?: (state?.incomePerHour ?: 0L).toString(),
                            onValueChange = {
                                incomeEdits[resource] = it
                                saved = false
                            },
                            label = "Income per hour",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (wanted > 0) {
                        val short = wanted - haveNow
                        Text(
                            "Wishlist needs ${Fmt.compact(wanted)}" + if (short > 0) ", short by ${Fmt.compact(short)}" else ", covered",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (short > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
        item(key = "save") {
            Button(
                enabled = amountEdits.isNotEmpty() || incomeEdits.isNotEmpty(),
                onClick = {
                    val updated = Resource.entries.mapNotNull { r ->
                        val a = amountEdits[r]
                        val i = incomeEdits[r]
                        if (a == null && i == null) return@mapNotNull null
                        val existing = resources[r]
                        ResourceState(
                            resource = r,
                            amount = a?.toLongOrNull() ?: existing?.amountAt(now) ?: 0L,
                            incomePerHour = i?.toLongOrNull() ?: existing?.incomePerHour ?: 0L,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                    vm.saveResources(updated)
                    amountEdits.clear()
                    incomeEdits.clear()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Saved" else "Save balances") }
        }
    }
}
