package com.livingbook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingbook.ai.AiException
import com.livingbook.ai.LlmMessage
import com.livingbook.ai.LlmProvider
import com.livingbook.ai.LlmRequest
import com.livingbook.data.AiMode
import com.livingbook.data.AppSettings
import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Choosing "My proxy" needs consent first; switching to Off or Demo never does. */
fun needsAiConsent(target: AiMode, consented: Boolean): Boolean = target == AiMode.PROXY && !consented

fun textSizeLabel(scale: Float): String = "${(scale * 100).roundToInt()}%"

/** Result of "Test connection"; [ok] is null while the check runs. */
data class ConnectionCheck(val ok: Boolean?, val message: String)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val llm: LlmProvider,
    private val conversations: ConversationRepository,
    private val learning: LearningProfileRepository,
) : ViewModel() {
    private var lastSave: Job? = null

    private val _check = MutableStateFlow<ConnectionCheck?>(null)
    val check: StateFlow<ConnectionCheck?> = _check.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        lastSave = viewModelScope.launch { settings.update(transform) }
    }

    fun testConnection() {
        if (_check.value?.let { it.ok == null } == true) return
        _check.value = ConnectionCheck(null, "Checking your proxy…")
        viewModelScope.launch {
            lastSave?.join() // the URL or token may have been typed a moment ago
            _check.value = try {
                llm.complete(LlmRequest(system = "Reply with OK.", messages = listOf(LlmMessage("user", "ping")), maxTokens = 5))
                ConnectionCheck(true, "Connected. Your proxy answered.")
            } catch (e: AiException) {
                ConnectionCheck(false, e.userMessage)
            }
        }
    }

    fun clearCheck() {
        _check.value = null
    }

    fun deleteConversations() {
        viewModelScope.launch {
            conversations.deleteAll()
            _notice.value = "All conversations deleted."
        }
    }

    fun deleteLearningProfile() {
        viewModelScope.launch {
            learning.deleteAll()
            _notice.value = "Learning profile deleted."
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }
}
