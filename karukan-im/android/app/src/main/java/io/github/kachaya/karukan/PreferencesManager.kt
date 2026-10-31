package io.github.kachaya.karukan

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * SharedPreferences の読み込みと設定（KarukanSettings）の生成を行う管理クラス
 */
class PreferencesManager(
    context: Context,
    private val onSettingsChanged: (KarukanSettings) -> Unit
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val defaultOptimalThreads: Int = KarukanEngine.calculateOptimalNThreads()

    var currentSettings: KarukanSettings = loadSettings()
        private set

    fun startListening() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        currentSettings = loadSettings()
    }

    fun stopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    /**
     * ライブ変換の有効/無効を設定する。
     */
    fun setLiveConversionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("pref_live_conversion", enabled) }
    }

    fun loadSettings(): KarukanSettings {
        val personaJson = prefs.getString("pref_persona_list", null)
        val personaItems = if (personaJson != null) {
            PersonaItem.fromJsonString(personaJson)
        } else {
            emptyList()
        }
        val activePersona = personaItems.firstOrNull { it.isSelected }?.text ?: ""

        val settings = KarukanSettings(
            modelName = prefs.getStringNonNull("pref_model_name", "jinen-v2-xsmall-Q5_K_M.gguf"),
            isLiveConversionEnabled = prefs.getBoolean("pref_live_conversion", true),
            numCandidates = prefs.getIntFromString("pref_num_candidates", 9),
            punctuationStyle = prefs.getStringNonNull("pref_punctuation", "kuten_touten"),
            spaceStyle = prefs.getStringNonNull("pref_space", "half"),

            strategy = prefs.getStringNonNull("pref_strategy", "adaptive"),
            nThreads = prefs.getIntFromString("pref_n_threads", defaultOptimalThreads),
            persona = activePersona,
            useContext = prefs.getBoolean("pref_use_context", true),
            contextChars = prefs.getIntFromString("pref_context_chars", 10),
            maxLatencyMs = prefs.getIntFromString("pref_max_latency_ms", 300),
            chunkChars = prefs.getIntFromString("pref_chunk_chars", 30),
            chunkSymbols = prefs.getIntFromString("pref_chunk_symbols", 1),
            chunkDigits = prefs.getIntFromString("pref_chunk_digits", 0),
            chunkAlphabets = prefs.getIntFromString("pref_chunk_alphabets", 0),
            beamWidth = prefs.getIntFromString("pref_beam_width", 9),
            beamChars = prefs.getIntFromString("pref_beam_chars", 30),

            isLearningEnabled = prefs.getBoolean("pref_learning_enabled", true),
            maxLearningEntries = prefs.getIntFromString("pref_max_learning_entries", 10000),

            isDateConversionEnabled = prefs.getBoolean("pref_date_conversion", true),
            isAutoCapitalizationEnabled = prefs.getBoolean("pref_auto_capitalization", true),
            bracketStyle = prefs.getStringNonNull("pref_bracket", "corner"),
            slashStyle = prefs.getStringNonNull("pref_slash", "middle_dot"),
            digitWidth = prefs.getStringNonNull("pref_width_digit", "half"),
            asciiWidth = prefs.getStringNonNull("pref_width_ascii", "full"),
            kanaSymbolWidth = prefs.getStringNonNull("pref_width_kana_symbol", "full"),

            keyboardLayout = prefs.getStringNonNull("pref_keyboard_layout", "qwerty"),
            isVibrationEnabled = prefs.getBoolean("pref_vibration_enabled", true),
            isSoundEnabled = prefs.getBoolean("pref_sound_enabled", false),
            isForceShowSoftKeyboardEnabled = prefs.getBoolean("pref_force_show_soft_keyboard", true),
            isAutoCommitEnabled = prefs.getBoolean("pref_auto_commit", true),
            keyboardHeight = prefs.getStringNonNull("pref_keyboard_height", "medium"),
            keyboardTheme = prefs.getStringNonNull("pref_keyboard_theme", "dark")
        )
        currentSettings = settings
        return settings
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val updated = loadSettings()
        onSettingsChanged(updated)
    }
}

private fun SharedPreferences.getStringNonNull(key: String, defaultValue: String): String {
    return getString(key, defaultValue) ?: defaultValue
}

private fun SharedPreferences.getIntFromString(key: String, defaultValue: Int): Int {
    return getString(key, null)?.toIntOrNull() ?: defaultValue
}
