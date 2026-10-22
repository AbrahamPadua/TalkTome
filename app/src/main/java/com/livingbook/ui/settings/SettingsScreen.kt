package com.livingbook.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livingbook.AppContainer
import com.livingbook.data.AiMode
import com.livingbook.data.AppSettings
import com.livingbook.data.ReadingFont
import com.livingbook.data.ThemeMode
import com.livingbook.model.Granularity
import com.livingbook.ui.reader.SPEEDS
import com.livingbook.ui.reader.VoicePickerDialog
import com.livingbook.ui.reader.rateLabel
import com.livingbook.ui.theme.readingStyle
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, settings: AppSettings, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(container.settings, container.llm, container.conversationsRepo, container.learning) }
    val check by vm.check.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val voices by container.narration.voices.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    var consentOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) } // "conversations" or "profile"

    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.consumeNotice() // after showing: consuming first would restart this effect and cancel the snackbar
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Section("Reading")
                TextSize(settings) { scale -> vm.update { it.copy(textScale = scale) } }
                Choice(
                    "Theme",
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                    settings.theme,
                ) { theme -> vm.update { it.copy(theme = theme) } }
                Choice("Font", listOf(ReadingFont.SERIF to "Serif", ReadingFont.SANS to "Sans"), settings.font) { font ->
                    vm.update { it.copy(font = font) }
                }
                Choice("Default topic size", Granularity.entries.map { it to it.label }, settings.granularity) { granularity ->
                    vm.update { it.copy(granularity = granularity) }
                }

                Section("Listening")
                ListItem(
                    headlineContent = { Text("Voice") },
                    supportingContent = { Text(voices.firstOrNull { it.id == settings.ttsVoice }?.label ?: "Phone default") },
                    trailingContent = { TextButton(onClick = { voicePickerOpen = true }) { Text("Change") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Text("Speed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SPEEDS.forEach { speed ->
                        FilterChip(
                            selected = abs(settings.ttsRate - speed) < 0.01f,
                            onClick = { container.session.setRate(speed) },
                            label = { Text(rateLabel(speed)) },
                        )
                    }
                }
                SwitchRow("Continue to the next topic", "Keep listening when a topic ends.", settings.autoplay) { on ->
                    vm.update { it.copy(autoplay = on) }
                }

                Section("Tutoring")
                SwitchRow("Tutoring suggestions", "Offer Explain, Explore and Challenge at the end of a topic.", settings.tutoringSuggestions) { on ->
                    vm.update { it.copy(tutoringSuggestions = on) }
                }
                SwitchRow("Spoken tutoring prompts", "Say the offers out loud in Listen mode.", settings.spokenPrompts) { on ->
                    vm.update { it.copy(spokenPrompts = on) }
                }
                SwitchRow("Comprehension checks", "Sometimes offer to check your understanding.", settings.comprehensionChecks) { on ->
                    vm.update { it.copy(comprehensionChecks = on) }
                }

                Section("AI")
                Choice(
                    "AI answers",
                    listOf(AiMode.OFF to "Off", AiMode.DEMO to "Demo", AiMode.PROXY to "My proxy"),
                    settings.aiMode,
                ) { mode ->
                    if (needsAiConsent(mode, settings.aiConsent)) consentOpen = true else vm.update { it.copy(aiMode = mode) }
                }
                Text(
                    when (settings.aiMode) {
                        AiMode.OFF -> "No AI. Reading and narration work fully offline; conversations and the guided view are off."
                        AiMode.DEMO -> "Answers are matching passages from your book, labeled as demo. Nothing leaves the device."
                        AiMode.PROXY -> "Questions go to your own proxy, which forwards them to the AI provider set up there."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (settings.aiMode == AiMode.PROXY) ProxyFields(settings, check, vm)
                SwitchRow("Speak AI replies", "Read answers aloud during a conversation.", settings.speakAiResponses) { on ->
                    vm.update { it.copy(speakAiResponses = on) }
                }

                Section("Privacy")
                Text(
                    "Books, notes and conversations are stored only on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                DangerButton("Delete all conversations") { confirm = "conversations" }
                DangerButton("Delete learning profile") { confirm = "profile" }
            }
        }
    }

    if (voicePickerOpen) {
        VoicePickerDialog(
            voices = voices,
            selected = settings.ttsVoice,
            onSelect = { container.session.setVoice(it); voicePickerOpen = false },
            onDismiss = { voicePickerOpen = false },
        )
    }
    if (consentOpen) {
        AlertDialog(
            onDismissRequest = { consentOpen = false },
            title = { Text("Send questions to your AI proxy?") },
            text = {
                Text(
                    "When you ask something, Living Book sends:\n" +
                        "• your question\n" +
                        "• the text of the current topic\n" +
                        "• a few matching passages from the book\n" +
                        "• a short summary of your learning profile\n\n" +
                        "It never sends the whole book. It goes to the proxy address you enter, which forwards it to the AI provider configured there."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { it.copy(aiMode = AiMode.PROXY, aiConsent = true) }
                    consentOpen = false
                }) { Text("Agree and turn on") }
            },
            dismissButton = { TextButton(onClick = { consentOpen = false }) { Text("Cancel") } },
        )
    }
    confirm?.let { what ->
        val conversations = what == "conversations"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (conversations) "Delete all conversations?" else "Delete your learning profile?") },
            text = {
                Text(
                    if (conversations) "Every conversation in every book is removed from this device. Your books, progress and notes stay."
                    else "Everything the app has noted about how you learn is removed. It starts again from your next answers."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (conversations) vm.deleteConversations() else vm.deleteLearningProfile()
                        confirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProxyFields(settings: AppSettings, check: ConnectionCheck?, vm: SettingsViewModel) {
    // Local field state: writing every keystroke through DataStore and back would move the cursor.
    var url by rememberSaveable { mutableStateOf(settings.proxyUrl) }
    var token by rememberSaveable { mutableStateOf(settings.proxyToken) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { value ->
                url = value
                vm.clearCheck()
                vm.update { it.copy(proxyUrl = value.trim()) }
            },
            label = { Text("Proxy address") },
            placeholder = { Text("http://192.168.1.20:8787") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { value ->
                token = value
                vm.clearCheck()
                vm.update { it.copy(proxyToken = value.trim()) }
            },
            label = { Text("Access token") },
            supportingText = { Text("The shared secret set on your proxy. Not an AI provider key.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        val running = check != null && check.ok == null
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::testConnection, enabled = !running) { Text("Test connection") }
            if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        check?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodyMedium,
                color = when (it.ok) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun TextSize(settings: AppSettings, onScale: (Float) -> Unit) {
    var scale by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Text size ${textSizeLabel(scale)}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = scale,
            onValueChange = { scale = it },
            onValueChangeFinished = { onScale(scale) },
            valueRange = 0.8f..1.6f,
            steps = 7,
        )
        Text("The quick brown fox reads by the window.", style = readingStyle(settings.font, scale))
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) { Text(label) }
}
