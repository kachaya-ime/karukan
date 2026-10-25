package io.github.kachaya.karukan

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * SharedPreferences の読み込みと設定（KarukanSettings）の生成を行う管理クラス
 */
class PreferencesManager(
    context: Context,
    private val onSettingsChanged: (KarukanSettings) -> Unit
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    var currentSettings: KarukanSettings = loadSettings()
        private set

    private val defaultOptimalThreads: Int = KarukanEngine(context).calculateOptimalNThreads()

    fun startListening() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        currentSettings = loadSettings()
    }

    fun stopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    fun loadSettings(): KarukanSettings {
        val settings = KarukanSettings(
            isKarukanEnabled = prefs.getBoolean("karukan_enabled", true),
            isLiveConversionEnabled = prefs.getBoolean("pref_live_conversion", true),
            numCandidates = prefs.getString("pref_num_candidates", "9")?.toIntOrNull() ?: 9,
            punctuationStyle = prefs.getString("pref_punctuation", "kuten_touten") ?: "kuten_touten",
            spaceStyle = prefs.getString("pref_space", "half") ?: "half",

            strategy = prefs.getString("pref_strategy", "adaptive") ?: "adaptive",
            nThreads = prefs.getString("pref_n_threads", null)?.toIntOrNull() ?: defaultOptimalThreads,
            persona = prefs.getString("pref_persona", "") ?: "",
            useContext = prefs.getBoolean("pref_use_context", true),
            contextChars = prefs.getString("pref_context_chars", "10")?.toIntOrNull() ?: 10,
            maxLatencyMs = prefs.getString("pref_max_latency_ms", "100")?.toIntOrNull() ?: 100,
            chunkChars = prefs.getString("pref_chunk_chars", "30")?.toIntOrNull() ?: 30,
            chunkSymbols = prefs.getString("pref_chunk_symbols", "1")?.toIntOrNull() ?: 1,
            chunkDigits = prefs.getString("pref_chunk_digits", "0")?.toIntOrNull() ?: 0,
            chunkAlphabets = prefs.getString("pref_chunk_alphabets", "0")?.toIntOrNull() ?: 0,
            beamWidth = prefs.getString("pref_beam_width", "3")?.toIntOrNull() ?: 3,
            beamChars = prefs.getString("pref_beam_chars", "30")?.toIntOrNull() ?: 30,

            isLearningEnabled = prefs.getBoolean("pref_learning_enabled", true),
            maxLearningEntries = prefs.getString("pref_max_learning_entries", "10000")?.toIntOrNull() ?: 10000,

            isDateConversionEnabled = prefs.getBoolean("pref_date_conversion", true),
            isAutoCapitalizationEnabled = prefs.getBoolean("pref_auto_capitalization", true),
            bracketStyle = prefs.getString("pref_bracket", "corner") ?: "corner",
            slashStyle = prefs.getString("pref_slash", "middle_dot") ?: "middle_dot",
            digitWidth = prefs.getString("pref_width_digit", "half") ?: "half",
            asciiWidth = prefs.getString("pref_width_ascii", "full") ?: "full",
            kanaSymbolWidth = prefs.getString("pref_width_kana_symbol", "full") ?: "full",

            keyboardLayout = prefs.getString("pref_keyboard_layout", "qwerty") ?: "qwerty",
            flickSensitivity = prefs.getString("pref_flick_sensitivity", "normal") ?: "normal",
            isVibrationEnabled = prefs.getBoolean("pref_vibration_enabled", true),
            isSoundEnabled = prefs.getBoolean("pref_sound_enabled", false),
            isAutoCommitEnabled = prefs.getBoolean("pref_auto_commit", true),
            keyboardHeight = prefs.getString("pref_keyboard_height", "medium") ?: "medium",
            keyboardTheme = prefs.getString("pref_keyboard_theme", "dark") ?: "dark"
        )
        currentSettings = settings
        return settings
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val updated = loadSettings()
        onSettingsChanged(updated)
    }
}