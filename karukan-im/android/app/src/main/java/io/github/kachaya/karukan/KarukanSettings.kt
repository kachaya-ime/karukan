package io.github.kachaya.karukan

/**
 * Karukan IME のすべての設定値を保持するイミュータブルデータクラス
 */
data class KarukanSettings(
    // 基本変換設定
    val modelName: String = "jinen-v2-xsmall-Q8_0.gguf",
    val isLiveConversionEnabled: Boolean = true,
    val liveConversionDebounceMs: Int = 100,
    val isShowCandidateAnnotationsEnabled: Boolean = true,
    val numCandidates: Int = 9,
    val punctuationStyle: String = "kuten_touten",
    val spaceStyle: String = "half",

    // パフォーマンス・詳細設定
    val strategy: String = "adaptive",
    val nThreads: Int = 4,
    val persona: String = "",
    val useContext: Boolean = true,
    val contextChars: Int = 10,
    val maxLatencyMs: Int = 300,
    val chunkChars: Int = 30,
    val chunkSymbols: Int = 1,
    val chunkDigits: Int = 0,
    val chunkAlphabets: Int = 0,
    val beamWidth: Int = 3,
    val beamChars: Int = 30,

    // 変換学習設定
    val isLearningEnabled: Boolean = true,
    val maxLearningEntries: Int = 10000,
    val maxLearningSurfaceChars: Int = 50,

    // 拡張設定（記号・幅・自動変換ルール）
    val isDateConversionEnabled: Boolean = true,
    val dateConfigJson: String = "",
    val isAutoCommitEnabled: Boolean = true,
    val isMoveCursorInsideBracketEnabled: Boolean = true,
    val bracketStyle: String = "corner",
    val slashStyle: String = "middle_dot",
    val digitWidth: String = "half",
    val asciiWidth: String = "full",
    val kanaSymbolWidth: String = "full",

    // キーボード UI 設定
    val keyboardLayout: String = "qwerty",
    val isVibrationEnabled: Boolean = true,
    val isSoundEnabled: Boolean = false,
    val isShowInputStatusBarEnabled: Boolean = false,
    val isShowFloatingCandidateWindowEnabled: Boolean = true,
    val isShowFloatingStatusTextEnabled: Boolean = false,
    val isShowPhysicalKeyboardSuggestCandidatesEnabled: Boolean = true,
    val keyboardHeight: String = "medium",
    val keyboardTheme: String = "dark",
    val physicalKeyboardLayout: String = "jis109",

    // カスタムキーショートカット設定 JSON
    val keyShortcutsJson: String = ""
)
