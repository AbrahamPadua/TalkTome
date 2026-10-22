package com.livingbook.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livingbook.model.ReadMode
import com.livingbook.session.SessionState
import com.livingbook.speech.NarrationState
import com.livingbook.speech.NarrationStatus
import com.livingbook.speech.VoiceOption
import kotlin.math.abs

val SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f)

/** "1.25×", "1×". */
fun rateLabel(rate: Float): String = (if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()) + "×"

@Composable
fun ModeToggle(mode: ReadMode, onMode: (ReadMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(ReadMode.READ to "Read", ReadMode.LISTEN to "Listen").forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** Bottom player shown in Listen mode. [extra] holds the optional tutoring card. */
@Composable
fun ListenPanel(
    state: SessionState,
    narration: NarrationState,
    rate: Float,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onRate: (Float) -> Unit,
    onVoice: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val playing = narration.status == NarrationStatus.PLAYING
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        extra()
        narration.errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            IconButton(onClick = onSkipBack) { Icon(Icons.Filled.FastRewind, contentDescription = "Previous sentence") }
            FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onSkipForward) { Icon(Icons.Filled.FastForward, contentDescription = "Next sentence") }
        }
        if (state.sentences.isNotEmpty()) {
            Text(
                "Sentence ${state.sentenceIndex + 1} of ${state.sentences.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SPEEDS.forEach { speed ->
                FilterChip(selected = abs(rate - speed) < 0.01f, onClick = { onRate(speed) }, label = { Text(rateLabel(speed)) })
            }
            AssistChip(
                onClick = onVoice,
                label = { Text("Voice") },
                leadingIcon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** Narration voice choice; selecting applies it and closes. Shared by the player and Settings. */
@Composable
fun VoicePickerDialog(voices: List<VoiceOption>, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Narration voice") },
        text = {
            LazyColumn {
                item { VoiceRow("Phone default", null, selected == null) { onSelect(null) } }
                items(voices, key = { it.id }) { voice ->
                    // The engine's label already names the language; only the network need is worth adding.
                    VoiceRow(voice.label, "Needs an internet connection".takeIf { voice.needsNetwork }, selected == voice.id) { onSelect(voice.id) }
                }
                if (voices.isEmpty()) {
                    item {
                        Text(
                            "No other voices found. You can add voices in your phone's text-to-speech settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun VoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
