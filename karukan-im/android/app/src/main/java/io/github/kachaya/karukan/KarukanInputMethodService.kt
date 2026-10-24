package io.github.kachaya.karukan

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY & 12キー フリック入力対応)
 */
class KarukanInputMethodService : InputMethodService() {

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    private lateinit var composingTextView: TextView
    private lateinit var candidateScrollContainer: HorizontalScrollView
    private lateinit var candidateLayout: LinearLayout

    private lateinit var preferencesManager: PreferencesManager
    private val settings: KarukanSettings
        get() = preferencesManager.currentSettings

    // モード管理 (かな / 英数)
    private var isEnglishMode: Boolean = false

    // 絵文字パレット・画面切り替え管理
    private lateinit var mainKeyboardContainer: View
    private lateinit var emojiPaletteContainer: LinearLayout
    private lateinit var emojiPaletteController: EmojiPaletteController
    private lateinit var keyboardLayoutBuilder: KeyboardLayoutBuilder

    // ライブ変換用状態
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveConversionExecutor = Executors.newSingleThreadExecutor()
    private var currentLiveConversionQueryId = 0L
    private var currentLiveCandidate: String? = null

    companion object {
        private const val TAG = "KarukanInputService"
    }

    private data class ThemeColors(
        val keyboardBg: Int,
        val candidateBg: Int,
        val textColor: Int,
        val candidateBtnBg: Int
    )

    private fun getThemeColors(): ThemeColors {
        val isDark = isDarkTheme()
        return if (isDark) {
            ThemeColors(
                keyboardBg = Color.parseColor("#202124"),
                candidateBg = Color.parseColor("#303134"),
                textColor = Color.WHITE,
                candidateBtnBg = Color.parseColor("#3C4043")
            )
        } else {
            ThemeColors(
                keyboardBg = Color.parseColor("#ECEFF1"),
                candidateBg = Color.parseColor("#F8F9FA"),
                textColor = Color.parseColor("#202124"),
                candidateBtnBg = Color.WHITE
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KarukanInputMethodService created")
        engine = KarukanEngine(this)

        preferencesManager = PreferencesManager(this) { updatedSettings ->
            if (::engine.isInitialized) {
                engine.setNThreads(updatedSettings.nThreads)
            }
            mainHandler.post {
                if (isInputViewShown) {
                    setInputView(onCreateInputView())
                }
            }
        }
        preferencesManager.startListening()

        if (::engine.isInitialized) {
            engine.setNThreads(settings.nThreads)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        preferencesManager.stopListening()
    }

    private fun isDarkTheme(): Boolean {
        return when (settings.keyboardTheme) {
            "light" -> false
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun triggerKeyFeedback() {
        if (settings.isVibrationEnabled) {
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

        if (settings.isSoundEnabled) {
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
        return when (settings.keyboardHeight) {
            "small" -> (42 * density).toInt()
            "large" -> (62 * density).toInt()
            else -> (52 * density).toInt()
        }
    }


    private fun getPunctuationComma(): String = InputTransformUtils.getPunctuationComma(settings.punctuationStyle, settings.kanaSymbolWidth)
    private fun getPunctuationPeriod(): String = InputTransformUtils.getPunctuationPeriod(settings.punctuationStyle, settings.kanaSymbolWidth)
    private fun getBracketLeft(): String = InputTransformUtils.getBracketLeft(settings.bracketStyle, settings.kanaSymbolWidth)
    private fun getBracketRight(): String = InputTransformUtils.getBracketRight(settings.bracketStyle, settings.kanaSymbolWidth)
    private fun getSlashChar(): String = InputTransformUtils.getSlashChar(settings.slashStyle, settings.kanaSymbolWidth)
    private fun getSpaceString(): String = InputTransformUtils.getSpaceString(settings.spaceStyle)

    private fun transformDigit(c: Char): String = InputTransformUtils.transformDigit(c, settings.digitWidth)
    private fun transformAsciiSymbol(c: Char): String = InputTransformUtils.transformAsciiSymbol(c, settings.asciiWidth)
    private fun getDateCandidates(reading: String): List<String> = InputTransformUtils.getDateCandidates(reading, settings.isDateConversionEnabled)

    override fun onCreateInputView(): View {
        val colors = getThemeColors()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.keyboardBg)
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
            setTextColor(colors.textColor)
        }
        rootLayout.addView(composingTextView)

        // 2. 変換候補バー (横スクロール可能)
        candidateScrollContainer = HorizontalScrollView(this).apply {
            setBackgroundColor(colors.candidateBg)
            setPadding(8, 4, 8, 4)
        }
        candidateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
        candidateScrollContainer.addView(candidateLayout)
        rootLayout.addView(candidateScrollContainer)

        keyboardLayoutBuilder = KeyboardLayoutBuilder(
            context = this,
            isDarkThemeSupplier = { isDarkTheme() },
            isShowCursorKeysEnabledSupplier = { settings.isShowCursorKeysEnabled },
            isShowEmojiButtonEnabledSupplier = { settings.isShowEmojiButtonEnabled },
            flickSensitivitySupplier = { settings.flickSensitivity },
            keyButtonHeightSupplier = { getKeyButtonHeight() },
            listener = keyboardEventListener
        )

        // 3. 画面キーボードの構築 (QWERTY または 12キー フリック)
        mainKeyboardContainer = if (settings.keyboardLayout == "flick") {
            keyboardLayoutBuilder.createFlickKeyboardContainer()
        } else {
            keyboardLayoutBuilder.createQwertyKeyboardContainer()
        }

        rootLayout.addView(mainKeyboardContainer)

        // 4. 絵文字パレット領域の構築 (初期状態は非表示)
        emojiPaletteController = EmojiPaletteController(
            context = this,
            engine = engine,
            isDarkThemeSupplier = { isDarkTheme() },
            keyboardHeightPxSupplier = { getKeyButtonHeight() * 4 + (32 * resources.displayMetrics.density).toInt() },
            onEmojiSelected = { emoji ->
                triggerKeyFeedback()
                currentInputConnection?.commitText(emoji, 1)
            },
            onCloseRequested = {
                triggerKeyFeedback()
                emojiPaletteController.hide(mainKeyboardContainer)
            }
        )
        emojiPaletteContainer = emojiPaletteController.createView()
        emojiPaletteContainer.visibility = View.GONE
        rootLayout.addView(emojiPaletteContainer)

        return rootLayout
    }

    private val keyboardEventListener = object : KeyboardLayoutBuilder.KeyboardEventListener {
        override fun onKeyFeedback() = triggerKeyFeedback()
        override fun onKeyPress(key: String) = this@KarukanInputMethodService.onKeyPress(key)
        override fun onFlickInput(char: String) = this@KarukanInputMethodService.onFlickInput(char)
        override fun onBackspace() = this@KarukanInputMethodService.onBackspace()
        override fun onSpaceOrConvert() {
            if (!isEnglishMode && romajiBuffer.isNotEmpty()) {
                performConversion()
            } else {
                currentInputConnection?.commitText(getSpaceString(), 1)
            }
        }

        override fun onCommit() = commitCurrentHiragana()
        override fun onClear() {
            romajiBuffer.clear()
            updateComposingText()
            clearCandidateBar()
        }

        override fun onToggleEmojiPalette() {
            emojiPaletteController.toggle(mainKeyboardContainer)
        }

        override fun onSendKeyEvent(keyCode: Int) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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


    private fun onKeyPress(key: String) {
        var inputKey = key
        if (isEnglishMode) {
            if (settings.isAutoCapitalizationEnabled && isFirstCharOfSentence()) {
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
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) {
            composingTextView.text = "かな: (なし)"
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            clearCandidateBar()
            return
        }

        val hiragana = engine.romajiToHiragana(raw)

        if (!settings.isLiveConversionEnabled || !settings.isKarukanEnabled) {
            currentLiveCandidate = hiragana
            composingTextView.text = "かな: $hiragana  [$raw]"
            currentInputConnection?.setComposingText(hiragana, 1)
            return
        }

        // ライブ変換: 画面へ即時反映しつつ、非同期で最上位候補を推論・プレビュー表示
        composingTextView.text = "かな: $hiragana  [$raw]"
        currentInputConnection?.setComposingText(hiragana, 1)

        val queryId = ++currentLiveConversionQueryId
        liveConversionExecutor.execute {
            val flushHiragana = engine.romajiToHiraganaFlush(raw)
            val candidates = fetchCandidates(flushHiragana)
            val topCandidate = candidates.firstOrNull() ?: flushHiragana

            mainHandler.post {
                if (queryId == currentLiveConversionQueryId && romajiBuffer.toString() == raw) {
                    currentLiveCandidate = topCandidate
                    composingTextView.text = "かな: $flushHiragana → $topCandidate  [$raw]"
                    currentInputConnection?.setComposingText(topCandidate, 1)
                    renderCandidates(flushHiragana, candidates)
                }
            }
        }
    }

    private fun fetchCandidates(hiragana: String): List<String> {
        if (hiragana.isEmpty()) return emptyList()

        var leftContext = ""
        if (settings.useContext) {
            leftContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars, 0)?.toString() ?: ""
        }
        val fullContext = if (settings.persona.isNotEmpty()) {
            if (leftContext.isNotEmpty()) "${settings.persona} $leftContext" else settings.persona
        } else {
            leftContext
        }

        val engineCandidates = if (settings.isKarukanEnabled) {
            engine.convert(hiragana, settings.numCandidates, fullContext)
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
        return combinedCandidates
    }

    private fun performConversion() {
        if (isEnglishMode) return

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        val combinedCandidates = fetchCandidates(hiragana)

        renderCandidates(hiragana, combinedCandidates)
    }

    private fun renderCandidates(hiragana: String, candidates: List<String>) {
        candidateLayout.removeAllViews()
        val colors = getThemeColors()

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
                backgroundTintList = ColorStateList.valueOf(colors.candidateBtnBg)
                setTextColor(colors.textColor)
                setOnClickListener {
                    triggerKeyFeedback()
                    if (settings.isLearningEnabled) {
                        engine.recordLearning(hiragana, cand)
                    }
                    currentInputConnection?.commitText(cand, 1)
                    romajiBuffer.clear()
                    currentLiveCandidate = null
                    updateComposingText()
                    clearCandidateBar()
                }
            }
            candidateLayout.addView(candBtn)
        }
    }

    private fun clearCandidateBar() {
        candidateLayout.removeAllViews()
        val colors = getThemeColors()
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
    }

    private fun commitCurrentHiragana() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        val commitTextValue = currentLiveCandidate ?: hiragana

        if (settings.isLearningEnabled && commitTextValue != hiragana) {
            engine.recordLearning(hiragana, commitTextValue)
        }

        currentInputConnection?.commitText(commitTextValue, 1)
        romajiBuffer.clear()
        currentLiveCandidate = null
        updateComposingText()
        clearCandidateBar()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (settings.isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
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
        if (settings.isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
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
