package com.livingbook.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livingbook.model.Granularity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ReadingFont { SERIF, SANS }

enum class AiMode {
    /** No AI at all. Reading and narration still work. */
    OFF,
    /** Offline demo: answers are matching book passages, clearly labeled as not AI. */
    DEMO,
    /** Real model through the configured backend proxy. */
    PROXY,
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val font: ReadingFont = ReadingFont.SERIF,
    val textScale: Float = 1f,
    val granularity: Granularity = Granularity.BALANCED,
    val ttsVoice: String? = null,
    val ttsRate: Float = 1f,
    val autoplay: Boolean = true,
    val tutoringSuggestions: Boolean = true,
    val spokenPrompts: Boolean = false,
    val comprehensionChecks: Boolean = false,
    val speakAiResponses: Boolean = true,
    val aiMode: AiMode = AiMode.DEMO,
    /** Set once the user has read and accepted what is sent to the AI service. */
    val aiConsent: Boolean = false,
    val proxyUrl: String = "",
    /** Shared secret for the user's own proxy. Not an API key for any AI provider. */
    val proxyToken: String = "",
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[THEME] = s.theme.name
            prefs[FONT] = s.font.name
            prefs[TEXT_SCALE] = s.textScale
            prefs[GRANULARITY] = s.granularity.name
            if (s.ttsVoice == null) prefs.remove(TTS_VOICE) else prefs[TTS_VOICE] = s.ttsVoice
            prefs[TTS_RATE] = s.ttsRate
            prefs[AUTOPLAY] = s.autoplay
            prefs[TUTORING] = s.tutoringSuggestions
            prefs[SPOKEN_PROMPTS] = s.spokenPrompts
            prefs[CHECKS] = s.comprehensionChecks
            prefs[SPEAK_AI] = s.speakAiResponses
            prefs[AI_MODE] = s.aiMode.name
            prefs[AI_CONSENT] = s.aiConsent
            prefs[PROXY_URL] = s.proxyUrl
            prefs[PROXY_TOKEN] = s.proxyToken
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enumOr(this[THEME], d.theme),
            font = enumOr(this[FONT], d.font),
            textScale = this[TEXT_SCALE] ?: d.textScale,
            granularity = enumOr(this[GRANULARITY], d.granularity),
            ttsVoice = this[TTS_VOICE],
            ttsRate = this[TTS_RATE] ?: d.ttsRate,
            autoplay = this[AUTOPLAY] ?: d.autoplay,
            tutoringSuggestions = this[TUTORING] ?: d.tutoringSuggestions,
            spokenPrompts = this[SPOKEN_PROMPTS] ?: d.spokenPrompts,
            comprehensionChecks = this[CHECKS] ?: d.comprehensionChecks,
            speakAiResponses = this[SPEAK_AI] ?: d.speakAiResponses,
            aiMode = enumOr(this[AI_MODE], d.aiMode),
            aiConsent = this[AI_CONSENT] ?: d.aiConsent,
            proxyUrl = this[PROXY_URL] ?: d.proxyUrl,
            proxyToken = this[PROXY_TOKEN] ?: d.proxyToken,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val FONT = stringPreferencesKey("font")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val GRANULARITY = stringPreferencesKey("granularity")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val AUTOPLAY = booleanPreferencesKey("autoplay")
        val TUTORING = booleanPreferencesKey("tutoring_suggestions")
        val SPOKEN_PROMPTS = booleanPreferencesKey("spoken_prompts")
        val CHECKS = booleanPreferencesKey("comprehension_checks")
        val SPEAK_AI = booleanPreferencesKey("speak_ai")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val AI_CONSENT = booleanPreferencesKey("ai_consent")
        val PROXY_URL = stringPreferencesKey("proxy_url")
        val PROXY_TOKEN = stringPreferencesKey("proxy_token")
    }
}
