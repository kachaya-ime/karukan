package io.github.kachaya.karukan

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY & 12キー フリック入力対応)
 */
class KarukanInputMethodService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    private lateinit var composingTextView: TextView
    private lateinit var candidateScrollContainer: HorizontalScrollView
    private lateinit var candidateLayout: LinearLayout
    private lateinit var btnModeToggle: Button

    private lateinit var prefs: SharedPreferences
    private var isKarukanEnabled: Boolean = true
    private var numCandidates: Int = 9
    private var punctuationStyle: String = "kuten_touten"
    private var spaceStyle: String = "half"

    // パフォーマンス・高度な設定
    private var strategy: String = "adaptive"
    private var nThreads: Int = 4
    private var persona: String = ""
    private var useContext: Boolean = true
    private var contextChars: Int = 10
    private var maxLatencyMs: Int = 100
    private var chunkChars: Int = 30
    private var chunkSymbols: Int = 1
    private var chunkDigits: Int = 0
    private var chunkAlphabets: Int = 0
    private var beamWidth: Int = 3
    private var beamChars: Int = 30

    // 変換学習設定
    private var isLearningEnabled: Boolean = true

    // 拡張設定（記号・幅・自動変換ルール）
    private var isDateConversionEnabled: Boolean = true
    private var isAutoCapitalizationEnabled: Boolean = true
    private var bracketStyle: String = "corner"
    private var slashStyle: String = "middle_dot"
    private var digitWidth: String = "half"
    private var asciiWidth: String = "full"
    private var kanaSymbolWidth: String = "full"

    // キーボード UI 設定
    private var keyboardLayout: String = "qwerty"
    private var flickSensitivity: String = "normal"
    private var isVibrationEnabled: Boolean = true
    private var isSoundEnabled: Boolean = false
    private var isAutoCommitEnabled: Boolean = true
    private var isBsLongPressClearEnabled: Boolean = true
    private var isShowCursorKeysEnabled: Boolean = true
    private var isShowEmojiButtonEnabled: Boolean = true
    private var keyboardHeight: String = "medium"
    private var keyboardTheme: String = "dark"

    // モード管理 (かな / 英数)
    private var isEnglishMode: Boolean = false

    companion object {
        private const val TAG = "KarukanInputService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KarukanInputMethodService created")
        engine = KarukanEngine(this)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        loadPreferences()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun loadPreferences() {
        isKarukanEnabled = prefs.getBoolean("karukan_enabled", true)
        numCandidates = prefs.getString("pref_num_candidates", "9")?.toIntOrNull() ?: 9
        punctuationStyle = prefs.getString("pref_punctuation", "kuten_touten") ?: "kuten_touten"
        spaceStyle = prefs.getString("pref_space", "half") ?: "half"

        strategy = prefs.getString("pref_strategy", "adaptive") ?: "adaptive"
        nThreads = prefs.getString("pref_n_threads", "4")?.toIntOrNull() ?: 4
        persona = prefs.getString("pref_persona", "") ?: ""
        useContext = prefs.getBoolean("pref_use_context", true)
        contextChars = prefs.getString("pref_context_chars", "10")?.toIntOrNull() ?: 10
        maxLatencyMs = prefs.getString("pref_max_latency_ms", "100")?.toIntOrNull() ?: 100
        chunkChars = prefs.getString("pref_chunk_chars", "30")?.toIntOrNull() ?: 30
        chunkSymbols = prefs.getString("pref_chunk_symbols", "1")?.toIntOrNull() ?: 1
        chunkDigits = prefs.getString("pref_chunk_digits", "0")?.toIntOrNull() ?: 0
        chunkAlphabets = prefs.getString("pref_chunk_alphabets", "0")?.toIntOrNull() ?: 0
        beamWidth = prefs.getString("pref_beam_width", "3")?.toIntOrNull() ?: 3
        beamChars = prefs.getString("pref_beam_chars", "30")?.toIntOrNull() ?: 30

        isLearningEnabled = prefs.getBoolean("pref_learning_enabled", true)

        isDateConversionEnabled = prefs.getBoolean("pref_date_conversion", true)
        isAutoCapitalizationEnabled = prefs.getBoolean("pref_auto_capitalization", true)
        bracketStyle = prefs.getString("pref_bracket", "corner") ?: "corner"
        slashStyle = prefs.getString("pref_slash", "middle_dot") ?: "middle_dot"
        digitWidth = prefs.getString("pref_width_digit", "half") ?: "half"
        asciiWidth = prefs.getString("pref_width_ascii", "full") ?: "full"
        kanaSymbolWidth = prefs.getString("pref_width_kana_symbol", "full") ?: "full"

        keyboardLayout = prefs.getString("pref_keyboard_layout", "qwerty") ?: "qwerty"
        flickSensitivity = prefs.getString("pref_flick_sensitivity", "normal") ?: "normal"
        isVibrationEnabled = prefs.getBoolean("pref_vibration_enabled", true)
        isSoundEnabled = prefs.getBoolean("pref_sound_enabled", false)
        isAutoCommitEnabled = prefs.getBoolean("pref_auto_commit", true)
        isBsLongPressClearEnabled = prefs.getBoolean("pref_bs_long_press_clear", true)
        isShowCursorKeysEnabled = prefs.getBoolean("pref_show_cursor_keys", true)
        isShowEmojiButtonEnabled = prefs.getBoolean("pref_show_emoji_button", true)
        keyboardHeight = prefs.getString("pref_keyboard_height", "medium") ?: "medium"
        keyboardTheme = prefs.getString("pref_keyboard_theme", "dark") ?: "dark"

        if (::engine.isInitialized) {
            engine.setNThreads(nThreads)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        loadPreferences()
    }

    private fun isDarkTheme(): Boolean {
        return when (keyboardTheme) {
            "light" -> false
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun triggerKeyFeedback() {
        if (isVibrationEnabled) {
            try {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(20)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibration feedback failed", e)
            }
        }

        if (isSoundEnabled) {
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
                audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
            } catch (e: Exception) {
                Log.w(TAG, "Sound feedback failed", e)
            }
        }
    }

    private fun getKeyButtonHeight(): Int {
        val density = resources.displayMetrics.density
        return when (keyboardHeight) {
            "small" -> (42 * density).toInt()
            "large" -> (62 * density).toInt()
            else -> (52 * density).toInt()
        }
    }

    private fun getFlickThresholdPx(): Float {
        val density = resources.displayMetrics.density
        return when (flickSensitivity) {
            "sensitive" -> 30f * density
            "insensitive" -> 80f * density
            else -> 50f * density
        }
    }

    private fun getPunctuationComma(): String = transformKanaSymbol(if (punctuationStyle == "comma_period") "，" else "、")
    private fun getPunctuationPeriod(): String = transformKanaSymbol(if (punctuationStyle == "comma_period") "．" else "。")
    private fun getBracketLeft(): String = transformKanaSymbol(if (bracketStyle == "square") "[" else "「")
    private fun getBracketRight(): String = transformKanaSymbol(if (bracketStyle == "square") "]" else "」")
    private fun getSlashChar(): String = transformKanaSymbol(if (slashStyle == "slash") "/" else "・")
    private fun getSpaceString(): String = if (spaceStyle == "full") "　" else " "

    private fun transformKanaSymbol(symbol: String): String {
        if (kanaSymbolWidth == "half") {
            return when (symbol) {
                "。" -> "｡"
                "、" -> "､"
                "「" -> "｢"
                "」" -> "｣"
                "・" -> "･"
                else -> symbol
            }
        }
        return symbol
    }

    private fun transformDigit(c: Char): String {
        return if (digitWidth == "full" && c in '0'..'9') {
            (c.code + 0xFEE0).toChar().toString()
        } else {
            c.toString()
        }
    }

    private fun transformAsciiSymbol(c: Char): String {
        return if (asciiWidth == "full" && c.code in 33..126 && c !in 'a'..'z' && c !in 'A'..'Z' && c !in '0'..'9') {
            (c.code + 0xFEE0).toChar().toString()
        } else {
            c.toString()
        }
    }

    private fun getDateCandidates(reading: String): List<String> {
        if (!isDateConversionEnabled) return emptyList()

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val minuteStr = String.format(Locale.getDefault(), "%02d", minute)
        val hourStr = String.format(Locale.getDefault(), "%02d", hour)

        return when (reading) {
            "きょう" -> listOf(
                "$year/$month/$day",
                "${year}年${month}月${day}日",
                "$month/$day"
            )
            "きのう" -> {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_MONTH, -1)
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                listOf("$y/$m/$d", "${y}年${m}月${d}日")
            }
            "あした", "あす" -> {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                listOf("$y/$m/$d", "${y}年${m}月${d}日")
            }
            "いま" -> listOf(
                "$hourStr:$minuteStr",
                "${hour}時${minute}分"
            )
            "にちじ" -> listOf(
                "$year/$month/$day $hourStr:$minuteStr",
                "${year}年${month}月${day}日 ${hour}時${minute}分"
            )
            else -> emptyList()
        }
    }

    override fun onCreateInputView(): View {
        val isDark = isDarkTheme()
        val bgColor = if (isDark) resources.getColor(R.color.keyboard_bg, theme) else Color.parseColor("#f1f3f4")
        val candidateBgColor = if (isDark) resources.getColor(R.color.candidate_bg, theme) else Color.parseColor("#e8eaed")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        val initialPaddingLeft = rootLayout.paddingLeft
        val initialPaddingTop = rootLayout.paddingTop
        val initialPaddingRight = rootLayout.paddingRight
        val initialPaddingBottom = rootLayout.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navInsets.bottom
            )
            insets
        }

        // 1. 未確定テキスト表示エリア (ひらがな + ローマ字)
        composingTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数モード" else "かな: (なし)"
            textSize = 14f
            setPadding(16, 8, 16, 8)
            setTextColor(textColor)
        }
        rootLayout.addView(composingTextView)

        // 2. 変換候補バー (横スクロール可能)
        candidateScrollContainer = HorizontalScrollView(this).apply {
            setBackgroundColor(candidateBgColor)
            setPadding(8, 4, 8, 4)
        }
        candidateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setTextColor(textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
        candidateScrollContainer.addView(candidateLayout)
        rootLayout.addView(candidateScrollContainer)

        // 3. 画面キーボードの構築 (QWERTY または 12キー フリック)
        val keyboardContainer = if (keyboardLayout == "flick") {
            createFlickKeyboardContainer()
        } else {
            createQwertyKeyboardContainer()
        }

        rootLayout.addView(keyboardContainer)

        return rootLayout
    }

    private fun createQwertyKeyboardContainer(): LinearLayout {
        val keyboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val row1Keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2Keys = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-")
        val row3Keys = listOf("z", "x", "c", "v", "b", "n", "m", ",", ".")

        keyboardContainer.addView(createKeyRow(row1Keys))
        keyboardContainer.addView(createKeyRow(row2Keys))
        keyboardContainer.addView(createRow3WithBackspace(row3Keys))
        keyboardContainer.addView(createActionRow())

        return keyboardContainer
    }

    private fun createFlickKeyboardContainer(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // 12キーフリックの定義: [ラベル, [Center, Left, Up, Right, Down]]
        val flickMap = listOf(
            listOf("あ", "あ", "い", "う", "え", "お"),
            listOf("か", "か", "き", "く", "け", "こ"),
            listOf("さ", "さ", "し", "す", "せ", "そ"),
            listOf("た", "た", "ち", "つ", "て", "と"),
            listOf("な", "な", "に", "ぬ", "ね", "の"),
            listOf("は", "は", "ひ", "ふ", "へ", "ほ"),
            listOf("ま", "ま", "み", "む", "め", "も"),
            listOf("や", "や", "ゃ", "ゆ", "ゅ", "よ"),
            listOf("ら", "ら", "り", "る", "れ", "ろ"),
            listOf("わ", "わ", "を", "ん", "ー", "〜"),
            listOf("゛゜", "゛", "゜", "小", "", ""),
            listOf("記号", "、", "。", "？", "！", "…")
        )

        for (r in 0..3) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            for (c in 0..2) {
                val idx = r * 3 + c
                val flickItem = flickMap[idx]
                val label = flickItem[0]
                val chars = flickItem.subList(1, flickItem.size)

                val btn = Button(this).apply {
                    text = label
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        getKeyButtonHeight(),
                        1f
                    ).apply {
                        setMargins(4, 4, 4, 4)
                    }

                    setupFlickTouchListener(this, chars)
                }
                rowLayout.addView(btn)
            }
            container.addView(rowLayout)
        }

        container.addView(createActionRow())
        return container
    }

    private fun setupFlickTouchListener(btn: Button, chars: List<String>) {
        var startX = 0f
        var startY = 0f

        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    triggerKeyFeedback()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.x
                    val endY = event.y
                    val dx = endX - startX
                    val dy = endY - startY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val threshold = getFlickThresholdPx()

                    val selectedChar = if (dist < threshold) {
                        chars[0] // Center
                    } else if (abs(dx) > abs(dy)) {
                        if (dx < 0) chars[1] else chars[3] // Left or Right
                    } else {
                        if (dy < 0) chars[2] else chars[4] // Up or Down
                    }

                    if (selectedChar.isNotEmpty()) {
                        onFlickInput(selectedChar)
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun onFlickInput(char: String) {
        if (char == "゛" || char == "゜" || char == "小") {
            // 濁点・半濁点・小文字化処理
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            // 確定済み直前文字に濁点付与等の簡易処理
            currentInputConnection?.commitText(char, 1)
        } else {
            if (isEnglishMode) {
                currentInputConnection?.commitText(char, 1)
            } else {
                // 直接ひらがなを入力領域へコミットまたはバッファへ追加
                currentInputConnection?.commitText(char, 1)
            }
        }
    }

    private fun createKeyRow(keys: List<String>): LinearLayout {
        val btnHeight = getKeyButtonHeight()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            for (key in keys) {
                val btn = Button(this@KarukanInputMethodService).apply {
                    text = key
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        btnHeight,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    setOnClickListener {
                        triggerKeyFeedback()
                        onKeyPress(key)
                    }
                }
                addView(btn)
            }
        }
    }

    private fun createRow3WithBackspace(keys: List<String>): LinearLayout {
        val btnHeight = getKeyButtonHeight()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            for (key in keys) {
                val btn = Button(this@KarukanInputMethodService).apply {
                    text = key
                    textSize = 16f
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        btnHeight,
                        1f
                    ).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    setOnClickListener {
                        triggerKeyFeedback()
                        onKeyPress(key)
                    }
                }
                addView(btn)
            }

            // ⌫ BS ボタン
            val btnBs = Button(this@KarukanInputMethodService).apply {
                text = "⌫"
                textSize = 16f
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1.5f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    triggerKeyFeedback()
                    onBackspace()
                }
                setOnLongClickListener {
                    if (isBsLongPressClearEnabled && romajiBuffer.isNotEmpty()) {
                        triggerKeyFeedback()
                        romajiBuffer.clear()
                        updateComposingText()
                        clearCandidateBar()
                        true
                    } else false
                }
            }
            addView(btnBs)
        }
    }

    private fun createActionRow(): LinearLayout {
        val btnHeight = getKeyButtonHeight()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // かな / 英数 切替ボタン
            btnModeToggle = Button(this@KarukanInputMethodService).apply {
                text = if (isEnglishMode) "あ" else "A"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    triggerKeyFeedback()
                    if (romajiBuffer.isNotEmpty()) {
                        commitCurrentHiragana()
                    }
                    isEnglishMode = !isEnglishMode
                    text = if (isEnglishMode) "あ" else "A"
                    updateComposingText()
                    clearCandidateBar()
                }
            }
            addView(btnModeToggle)

            // カーソル移動ボタン (← / →)
            if (isShowCursorKeysEnabled) {
                val btnLeft = Button(this@KarukanInputMethodService).apply {
                    text = "←"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                    setOnClickListener {
                        triggerKeyFeedback()
                        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
                    }
                }
                val btnRight = Button(this@KarukanInputMethodService).apply {
                    text = "→"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                    setOnClickListener {
                        triggerKeyFeedback()
                        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                    }
                }
                addView(btnLeft)
                addView(btnRight)
            }

            // 絵文字ボタン
            if (isShowEmojiButtonEnabled) {
                val btnEmoji = Button(this@KarukanInputMethodService).apply {
                    text = "😊"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                    setOnClickListener {
                        triggerKeyFeedback()
                        renderEmojiPalette()
                    }
                }
                addView(btnEmoji)
            }

            // Space / 変換 ボタン
            val btnSpace = Button(this@KarukanInputMethodService).apply {
                text = "スペース / 変換"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1.8f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    triggerKeyFeedback()
                    if (!isEnglishMode && romajiBuffer.isNotEmpty()) {
                        performConversion()
                    } else {
                        currentInputConnection?.commitText(getSpaceString(), 1)
                    }
                }
            }

            // 確定 ボタン
            val btnCommit = Button(this@KarukanInputMethodService).apply {
                text = "確定"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    triggerKeyFeedback()
                    commitCurrentHiragana()
                }
            }

            // クリア ボタン
            val btnClear = Button(this@KarukanInputMethodService).apply {
                text = "クリア"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                setOnClickListener {
                    triggerKeyFeedback()
                    romajiBuffer.clear()
                    updateComposingText()
                    clearCandidateBar()
                }
            }

            addView(btnSpace)
            addView(btnCommit)
            addView(btnClear)
        }
    }

    private fun renderEmojiPalette() {
        candidateLayout.removeAllViews()
        val emojis = listOf("😊", "👍", "🎉", "❤️", "✨", "⭐", "🔥", "💡", "🎵", "👀", "🙏", "🙌", "😭", "🤣", "♪", "★", "○", "◎", "▲", "※")
        for (emoji in emojis) {
            val emojiBtn = Button(this).apply {
                text = emoji
                textSize = 16f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    triggerKeyFeedback()
                    currentInputConnection?.commitText(emoji, 1)
                    clearCandidateBar()
                }
            }
            candidateLayout.addView(emojiBtn)
        }
    }

    private fun onKeyPress(key: String) {
        var inputKey = key
        if (isEnglishMode) {
            if (isAutoCapitalizationEnabled && isFirstCharOfSentence()) {
                inputKey = inputKey.uppercase(Locale.getDefault())
            }
            currentInputConnection?.commitText(inputKey, 1)
            return
        }

        when (inputKey) {
            "," -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                currentInputConnection?.commitText(getPunctuationComma(), 1)
            }
            "." -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                currentInputConnection?.commitText(getPunctuationPeriod(), 1)
            }
            "[" -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                currentInputConnection?.commitText(getBracketLeft(), 1)
            }
            "]" -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                currentInputConnection?.commitText(getBracketRight(), 1)
            }
            "/" -> {
                if (romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                currentInputConnection?.commitText(getSlashChar(), 1)
            }
            else -> {
                if (inputKey.length == 1 && inputKey[0] in '0'..'9') {
                    if (romajiBuffer.isNotEmpty()) {
                        commitCurrentHiragana()
                    }
                    currentInputConnection?.commitText(transformDigit(inputKey[0]), 1)
                } else {
                    romajiBuffer.append(inputKey)
                    updateComposingText()
                }
            }
        }
    }

    private fun isFirstCharOfSentence(): Boolean {
        val textBefore = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        return textBefore.isEmpty() || textBefore.endsWith(". ") || textBefore.endsWith("\n")
    }

    private fun onBackspace() {
        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteCharAt(romajiBuffer.length - 1)
            updateComposingText()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun updateComposingText() {
        if (isEnglishMode) {
            composingTextView.text = "英数モード"
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) {
            composingTextView.text = "かな: (なし)"
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val hiragana = engine.romajiToHiragana(raw)
        composingTextView.text = "かな: $hiragana  [$raw]"
        currentInputConnection?.setComposingText(hiragana, 1)
    }

    private fun performConversion() {
        if (isEnglishMode) return

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)

        var leftContext = ""
        if (useContext) {
            leftContext = currentInputConnection?.getTextBeforeCursor(contextChars, 0)?.toString() ?: ""
        }
        val fullContext = if (persona.isNotEmpty()) {
            if (leftContext.isNotEmpty()) "$persona $leftContext" else persona
        } else {
            leftContext
        }

        val engineCandidates = if (isKarukanEnabled) {
            engine.convert(hiragana, numCandidates, fullContext)
        } else {
            listOf(hiragana)
        }

        val dateCandidates = getDateCandidates(hiragana)
        val combinedCandidates = mutableListOf<String>()
        for (cand in dateCandidates) {
            if (!combinedCandidates.contains(cand)) {
                combinedCandidates.add(cand)
            }
        }
        for (cand in engineCandidates) {
            if (!combinedCandidates.contains(cand)) {
                combinedCandidates.add(cand)
            }
        }

        renderCandidates(hiragana, combinedCandidates)
    }

    private fun renderCandidates(hiragana: String, candidates: List<String>) {
        candidateLayout.removeAllViews()

        val allCandidates = mutableListOf<String>()
        if (candidates.isNotEmpty()) {
            allCandidates.addAll(candidates)
        }
        if (!allCandidates.contains(hiragana)) {
            allCandidates.add(hiragana)
        }

        for (cand in allCandidates) {
            val candBtn = Button(this).apply {
                text = cand
                textSize = 16f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    triggerKeyFeedback()
                    if (isLearningEnabled) {
                        engine.recordLearning(hiragana, cand)
                    }
                    currentInputConnection?.commitText(cand, 1)
                    romajiBuffer.clear()
                    updateComposingText()
                    clearCandidateBar()
                }
            }
            candidateLayout.addView(candBtn)
        }
    }

    private fun clearCandidateBar() {
        candidateLayout.removeAllViews()
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
        }
        candidateLayout.addView(defaultCandidateTextView)
    }

    private fun commitCurrentHiragana() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        currentInputConnection?.commitText(hiragana, 1)
        romajiBuffer.clear()
        updateComposingText()
        clearCandidateBar()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
        romajiBuffer.clear()
        if (::composingTextView.isInitialized) {
            updateComposingText()
            clearCandidateBar()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.isPrintingKey) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0) {
                val c = unicodeChar.toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
                    onKeyPress(c.lowercaseChar().toString())
                    return true
                } else if (c == ',' || c == '.' || c == '[' || c == ']' || c == '/') {
                    onKeyPress(c.toString())
                    return true
                } else if (c in '0'..'9') {
                    onKeyPress(c.toString())
                    return true
                } else if (c.code in 33..126) {
                    if (!isEnglishMode && romajiBuffer.isNotEmpty()) commitCurrentHiragana()
                    currentInputConnection?.commitText(transformAsciiSymbol(c), 1)
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onBackspace()
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                if (!isEnglishMode && romajiBuffer.isNotEmpty()) {
                    performConversion()
                } else {
                    currentInputConnection?.commitText(getSpaceString(), 1)
                }
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                if (!isEnglishMode && romajiBuffer.isNotEmpty()) {
                    commitCurrentHiragana()
                }
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}
