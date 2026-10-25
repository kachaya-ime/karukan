package io.github.kachaya.karukan

/**
 * Karukan IME のすべての設定値を保持するイミュータブルデータクラス
 */
data class KarukanSettings(
    // 基本変換設定
    val isKarukanEnabled: Boolean = true,
    val isLiveConversionEnabled: Boolean = true,
    val numCandidates: Int = 9,
    val punctuationStyle: String = "kuten_touten",
    val spaceStyle: String = "half",

    // パフォーマンス・高度な設定
    val strategy: String = "adaptive",
    val nThreads: Int = 4,
    val persona: String = "",
    val useContext: Boolean = true,
    val contextChars: Int = 10,
    val maxLatencyMs: Int = 100,
    val chunkChars: Int = 30,
    val chunkSymbols: Int = 1,
    val chunkDigits: Int = 0,
    val chunkAlphabets: Int = 0,
    val beamWidth: Int = 3,
    val beamChars: Int = 30,

    // 変換学習設定
    val isLearningEnabled: Boolean = true,
    val maxLearningEntries: Int = 10000,

    // 拡張設定（記号・幅・自動変換ルール）
    val isDateConversionEnabled: Boolean = true,
    val isAutoCapitalizationEnabled: Boolean = true,
    val isAutoCommitEnabled: Boolean = true,
    val bracketStyle: String = "corner",
    val slashStyle: String = "middle_dot",
    val digitWidth: String = "half",
    val asciiWidth: String = "full",
    val kanaSymbolWidth: String = "full",

    // キーボード UI 設定
    val keyboardLayout: String = "qwerty",
    val flickSensitivity: String = "normal",
    val isVibrationEnabled: Boolean = true,
    val isSoundEnabled: Boolean = false,
    val keyboardHeight: String = "medium",
    val keyboardTheme: String = "dark"
)
