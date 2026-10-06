@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.buildersledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buildersledger.domain.Resource
import com.buildersledger.domain.TimeFormat
import kotlinx.coroutines.delay

/** A clock that ticks, so countdowns stay live without any timers in the ViewModel. */
@Composable
fun rememberNowMs(intervalMs: Long = 1000L): State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now.value = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) action()
        }
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (selected != null) "$label: ${optionLabel(selected)}" else label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Digits-only field. Keeps state as text so partially typed numbers behave. */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = if (supporting != null) ({ Text(supporting) }) else null,
        modifier = modifier,
    )
}

/** Free-text duration such as "2d 3h 15m", checked live against [TimeFormat.parse]. */
@Composable
fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val parsed = TimeFormat.parse(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotBlank() && parsed == null,
        supportingText = {
            Text(
                when {
                    value.isBlank() -> "As shown in the game, e.g. 2d 3h 15m"
                    parsed == null -> "Use d, h, m and s, for example 2d 3h 15m"
                    else -> "= ${TimeFormat.duration(parsed)}"
                }
            )
        },
        modifier = modifier,
    )
}

@Composable
fun ResourcePicker(selected: Resource, onSelected: (Resource) -> Unit, modifier: Modifier = Modifier) {
    DropdownField(
        label = "Resource",
        options = Resource.entries,
        selected = selected,
        optionLabel = { it.label },
        onSelected = onSelected,
        modifier = modifier,
    )
}

/** Name suggestions from labels and history, as tappable chips. */
@Composable
fun SuggestionRow(query: String, suggestions: List<String>, onPick: (String) -> Unit) {
    val matches = remember(query, suggestions) {
        if (query.isBlank()) emptyList()
        else suggestions.filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }.take(6)
    }
    if (matches.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        matches.forEach { AssistChip(onClick = { onPick(it) }, label = { Text(it) }) }
    }
}

@Composable
fun PriorityPicker(priority: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Priority (5 is done first)", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { p ->
                FilterChip(selected = priority == p, onClick = { onChange(p) }, label = { Text("$p") })
            }
        }
    }
}
