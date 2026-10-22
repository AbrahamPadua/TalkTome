package com.livingbook.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.db.LearningSignalEntity
import com.livingbook.data.db.SignalKind
import kotlinx.coroutines.launch

/** Signals grouped in a fixed order (Preferences, Needs another look, Understood); empty groups are left out. */
fun groupSignals(signals: List<LearningSignalEntity>): List<Pair<SignalKind, List<LearningSignalEntity>>> =
    SignalKind.entries.mapNotNull { kind -> signals.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it } }

fun SignalKind.title(): String = when (this) {
    SignalKind.PREFERENCE -> "Preferences"
    SignalKind.STRUGGLE -> "Needs another look"
    SignalKind.UNDERSTOOD -> "Understood"
}

/** The inspectable, editable learning profile. Small enough to need no ViewModel: it is the repository's flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(learning: LearningProfileRepository, onBack: () -> Unit) {
    val signals by remember { learning.observe() }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learning profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (!signals.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                },
            )
        },
    ) { padding ->
        val list = signals ?: return@Scaffold
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "about") {
                    Text(
                        "Built only from your explicit feedback and answers. It stays on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet. It fills in as you ask for examples, answer quizzes or explain ideas back.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                groupSignals(list).forEach { (kind, group) ->
                    item(key = kind.name) {
                        Text(
                            kind.title(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(group, key = { it.id }) { signal ->
                        SignalRow(
                            signal = signal,
                            editing = editing == signal.id,
                            onEdit = { editing = signal.id },
                            onSave = { label ->
                                scope.launch { learning.update(signal.copy(label = label)) }
                                editing = null
                            },
                            onCancel = { editing = null },
                            onDelete = { scope.launch { learning.delete(signal.id) } },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear your learning profile?") },
            text = { Text("Everything listed here is removed. It starts again from your next answers.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { learning.deleteAll() }
                        confirmClear = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignalRow(
    signal: LearningSignalEntity,
    editing: Boolean,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    if (editing) {
        var label by rememberSaveable(signal.id) { mutableStateOf(signal.label) }
        val save = { if (label.isNotBlank()) onSave(label.trim()) }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = save, enabled = label.isNotBlank()) { Icon(Icons.Outlined.Check, contentDescription = "Save label") }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel editing") }
        }
        return
    }
    ListItem(
        headlineContent = { Text(signal.label) },
        supportingContent = {
            Column {
                Text(signal.evidence)
                if (signal.count > 1) Text("Noted ${signal.count} times", style = MaterialTheme.typography.labelSmall)
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${signal.label}") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${signal.label}") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
