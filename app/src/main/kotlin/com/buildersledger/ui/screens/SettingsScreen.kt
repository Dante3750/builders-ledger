@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.LedgerApplication
import com.buildersledger.domain.WorkerPool
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.NumberField
import com.buildersledger.ui.components.SectionTitle
import com.buildersledger.ui.components.VillageDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: LedgerViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val scheduler = remember { (context.applicationContext as LedgerApplication).container.scheduler }
    val scope = rememberCoroutineScope()

    val village by vm.currentVillage.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val lead by vm.leadMinutes.collectAsStateWithLifecycle()
    val patience by vm.patienceHours.collectAsStateWithLifecycle()

    // Permission state can change while the system settings are open, so re-check on resume.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notificationsOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactOn = remember(refresh) { scheduler.canScheduleExact() }

    var message by remember { mutableStateOf<String?>(null) }
    var editVillage by remember { mutableStateOf(false) }
    var confirmDeleteVillage by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.exportBackup { json ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    message = if (ok) "Backup saved." else "Could not write the backup file."
                }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (text.isBlank()) message = "Could not read that file." else pendingRestore = text
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
        }

        // ---- notifications ----
        SectionTitle("Notifications")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notificationsOn) {
                    Text("Notifications are on.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Notifications are off, so you will not hear when a worker is free.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    }) { Text("Turn on notifications") }
                }
                HorizontalDivider()
                if (exactOn) {
                    Text("Exact timing is allowed. You will be told the moment an upgrade finishes.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "Exact timing is not allowed, so Android may delay reminders by a few minutes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (Build.VERSION.SDK_INT >= 31) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("Allow exact reminders") }
                    }
                }
                HorizontalDivider()
                Text("Also remind me before an upgrade finishes", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 h").forEach { (minutes, label) ->
                        FilterChip(selected = lead == minutes, onClick = { vm.setLeadMinutes(minutes) }, label = { Text(label) })
                    }
                }
            }
        }

        // ---- planner ----
        SectionTitle("Planner")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How long may a worker wait for resources before the planner fills the gap with something cheaper?", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 4, 8, 24).forEach { hours ->
                        FilterChip(
                            selected = patience == hours,
                            onClick = { vm.setPatienceHours(hours) },
                            label = { Text(if (hours == 0) "Never" else "${hours}h") },
                        )
                    }
                }
            }
        }

        // ---- village and pools ----
        SectionTitle("Village")
        val current = village
        if (current != null) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${current.name} · Town Hall ${current.townHall}" + (current.playerTag?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editVillage = true }) { Text("Edit") }
                        TextButton(onClick = { confirmDeleteVillage = true }) {
                            Text("Delete village", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                    Text("Workers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Builders, laboratory, pet house and so on. Set how many of each you have; the app never assumes game rules.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pools.forEach { pool -> PoolEditor(pool, onSave = { vm.savePool(it) }, onDelete = { vm.deletePool(pool.id) }) }
                    OutlinedButton(onClick = {
                        vm.savePool(WorkerPool(0, current.id, "New worker", 1, pools.size))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add worker type")
                    }
                }
            }
        }

        ApiSettingsSection(vm)

        // ---- backup ----
        SectionTitle("Backup")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Everything lives on this device only. Save a backup file to move to a new phone or to be safe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch("builders-ledger-backup.json") }) { Text("Save backup") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Restore")
                    }
                }
            }
        }

        // ---- about ----
        SectionTitle("About")
        Text(
            "Builder's Ledger is a fan-made planning tool. This material is unofficial and is not endorsed by Supercell. " +
                "For more information see Supercell's Fan Content Policy. It reads nothing from the game itself: " +
                "you paste the game's own data export or type values in. Your data stays on your device; the only network " +
                "use is the optional, off-by-default official-API progress sync above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editVillage && village != null) {
        VillageDialog(
            initial = village,
            onDismiss = { editVillage = false },
            onSave = { name, th, _ ->
                vm.updateVillage(village!!.copy(name = name, townHall = th))
                editVillage = false
            },
        )
    }

    if (confirmDeleteVillage && village != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVillage = false },
            title = { Text("Delete ${village!!.name}?") },
            text = { Text("Its upgrades, plans, balances and history will be removed. This cannot be undone. Save a backup first if unsure.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteVillage(village!!.id)
                    confirmDeleteVillage = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVillage = false }) { Text("Keep") } },
        )
    }

    pendingRestore?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace everything?") },
            text = { Text("Restoring replaces all villages, plans and history on this device with the contents of the backup.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreBackup(text) { result ->
                        message = result.fold(
                            onSuccess = { "Backup restored." },
                            onFailure = { it.message ?: "Could not restore that file." },
                        )
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PoolEditor(pool: WorkerPool, onSave: (WorkerPool) -> Unit, onDelete: () -> Unit) {
    var name by remember(pool) { mutableStateOf(pool.name) }
    var slots by remember(pool) { mutableStateOf(pool.slots.toString()) }
    var confirm by remember { mutableStateOf(false) }
    val slotCount = slots.toIntOrNull()
    val changed = name.trim() != pool.name || slotCount != pool.slots
    val valid = name.isNotBlank() && slotCount != null && slotCount in 0..12

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            NumberField(slots, { slots = it }, "Slots", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (changed) {
                TextButton(enabled = valid, onClick = { onSave(pool.copy(name = name.trim(), slots = slotCount ?: pool.slots)) }) {
                    Text("Save changes")
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete worker type") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${pool.name}?") },
            text = { Text("Running upgrades and planned items that use this worker type are removed with it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}
