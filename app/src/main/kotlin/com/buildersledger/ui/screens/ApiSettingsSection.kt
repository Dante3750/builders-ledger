package com.buildersledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buildersledger.domain.PlayerApi
import com.buildersledger.ui.LedgerViewModel
import com.buildersledger.ui.components.SectionTitle

@Composable
fun ApiSettingsSection(vm: LedgerViewModel) {
    val savedTag by vm.apiTag.collectAsStateWithLifecycle()
    val savedKey by vm.apiKey.collectAsStateWithLifecycle()
    val savedBase by vm.apiBaseUrl.collectAsStateWithLifecycle()

    // Re-seed the fields when the stored values first arrive or change from elsewhere.
    var tag by remember(savedTag) { mutableStateOf(savedTag) }
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var base by remember(savedBase) { mutableStateOf(savedBase) }
    var message by remember { mutableStateOf<String?>(null) }

    SectionTitle("Progress from official API (optional)")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Reads troop, spell, hero and equipment levels from the game's official developer API. It cannot see buildings, " +
                    "upgrade timers, resources or builder counts. This is the only feature that uses the internet.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it },
                label = { Text("Player tag") },
                placeholder = { Text("#2PP0...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = base,
                onValueChange = { base = it },
                label = { Text("API base URL") },
                placeholder = { Text(PlayerApi.DEFAULT_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Leave the URL empty for the official host. Official keys only work from the IP address you registered, " +
                    "which a phone rarely keeps, so many people use a proxy URL. A proxy you do not run yourself can see your key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The key is stored on this device in app-private storage and is not encrypted. It is sent only to the URL above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message = vm.saveApiSettings(tag, key, base) ?: "Saved."
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    tag = ""; key = ""; base = ""
                    message = vm.saveApiSettings("", "", "") ?: "Cleared."
                }) { Text("Clear") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
