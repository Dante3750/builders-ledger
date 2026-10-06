@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.data.ImportSummary
import com.buildersledger.domain.ImportException
import com.buildersledger.domain.TimeFormat
import com.buildersledger.domain.VillageImport
import com.buildersledger.domain.VillageImportResult
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.EmptyState
import com.buildersledger.ui.components.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportScreen(vm: LedgerViewModel, padding: PaddingValues, onDone: () -> Unit) {
    val village by vm.currentVillage.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val current = village

    if (current == null) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState("No village yet", "Add a village on the Board tab first.")
        }
        return
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var parsed by remember { mutableStateOf<VillageImportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<ImportSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    val names = remember { mutableStateMapOf<Long, String>() }

    fun load(text: String) {
        summary = null
        names.clear()
        try {
            parsed = VillageImport.parse(text)
            error = null
        } catch (e: ImportException) {
            parsed = null
            error = e.message
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (text.isBlank()) {
                    parsed = null
                    error = "Could not read that file."
                } else {
                    load(text)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync from your village export", style = MaterialTheme.typography.titleMedium)
                Text(
                    "In the game, open Settings, then More Settings, then Data Export, and copy the text. " +
                        "Come back here and paste it, or choose a saved file. The app reads the running timers " +
                        "and keeps your board in sync. Run it as often as you like: it updates instead of duplicating.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val text = clipboard.getText()?.text.orEmpty()
                        if (text.isBlank()) {
                            parsed = null
                            error = "The clipboard is empty. Copy the export in the game first."
                        } else {
                            load(text)
                        }
                    }) { Text("Paste from clipboard") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Choose file") }
                }
            }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        summary?.let { s ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Synced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "${s.added} added, ${s.updated} updated, ${s.finished} finished or removed, ${s.unchanged} unchanged. Reminders are set.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Button(onClick = onDone) { Text("Back to the board") }
                }
            }
        }

        parsed?.let { result ->
            val known = current.playerTag
            if (known != null && result.playerTag != null && !known.equals(result.playerTag, ignoreCase = true)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "This export belongs to ${result.playerTag}, but this village was synced with $known. " +
                            "Importing it here would mix two accounts. Switch village first if that is not what you want.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${result.active.size} running upgrade${if (result.active.size == 1) "" else "s"} found" +
                            (result.playerTag?.let { " for $it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    result.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val exportedAt = result.exportedAtMs ?: System.currentTimeMillis()
                    result.active.sortedBy { it.timerSeconds }.forEach { entry ->
                        val label = labels[entry.dataId]
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (label != null) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            } else {
                                OutlinedTextField(
                                    value = names[entry.dataId] ?: "",
                                    onValueChange = { names[entry.dataId] = it },
                                    label = { Text("Name this one (${entry.category} #${entry.dataId})") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                "level ${entry.level} → ${entry.level + 1} · ${TimeFormat.duration(entry.timerSeconds)} left · ready ${
                                    Fmt.whenText(exportedAt + entry.timerSeconds * 1000L)
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (result.active.any { labels[it.dataId] == null }) {
                        Text(
                            "The game's export has ids, not names. Name each one once and the app remembers it for every future sync. You can skip it and name items later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            vm.applyImport(result, names.toMap()) { outcome ->
                                busy = false
                                outcome.onSuccess {
                                    summary = it
                                    parsed = null
                                    error = null
                                }.onFailure {
                                    error = it.message ?: "Import failed."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Syncing..." else "Apply sync") }
                }
            }
        }
    }
}
