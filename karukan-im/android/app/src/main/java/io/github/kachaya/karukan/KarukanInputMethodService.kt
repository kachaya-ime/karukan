package io.github.kachaya.karukan

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.StateSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY & 12キー フリック入力対応)
 */
class KarukanInputMethodService : InputMethodService() {

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    private lateinit var composingTextView: TextView

    // 候補表示領域関連
    private lateinit var candidateBarContainer: LinearLayout
    private lateinit var candidateScrollContainer: HorizontalScrollView
    private lateinit var candidateLayout: LinearLayout
    private lateinit var expandButton: Button
    private var isCandidateExpanded: Boolean = false

    // 複数行候補表示パネル領域
    private lateinit var candidateExpandedContainer: LinearLayout
    private lateinit var expandedHeaderTitle: TextView
    private lateinit var candidateFlowLayout: CandidateFlowLayout
    private lateinit var expandedScrollView: ScrollView

    private lateinit var preferencesManager: PreferencesManager
    private val settings: KarukanSettings
        get() = preferencesManager.currentSettings

    // モード管理 (かな / 英数)
    private var isEnglishMode: Boolean = false

    // エディタ情報管理
    private var currentEditorInfo: EditorInfo? = null

    // 絵文字パレット・画面切り替え管理
    private lateinit var mainKeyboardContainer: View
    private lateinit var emojiPaletteContainer: LinearLayout
    private lateinit var emojiPaletteController: EmojiPaletteController
    private lateinit var keyboardLayoutBuilder: KeyboardLayoutBuilder

    // ライブ変換用状態
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveConversionExecutor = Executors.newSingleThreadExecutor()
    private var liveConversionFuture: Future<*>? = null
    private var liveConversionRunnable: Runnable? = null
    private var currentLiveConversionQueryId = 0L
    private var currentLiveCandidate: String? = null

    companion object {
        private const val TAG = "KarukanInputService"
        private const val LIVE_CONVERSION_DEBOUNCE_MS = 100L
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
        setTheme(R.style.Theme_Karukan)
        super.onCreate()
        Log.i(TAG, "KarukanInputMethodService created")
        engine = KarukanEngine(this)

        preferencesManager = PreferencesManager(this) { updatedSettings ->
            if (::engine.isInitialized) {
                engine.updateEngineConfig(updatedSettings)
            }
            mainHandler.post {
                if (isInputViewShown) {
                    setInputView(onCreateInputView())
                }
            }
        }
        preferencesManager.startListening()

        if (::engine.isInitialized) {
            engine.updateEngineConfig(settings)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveConversionFuture?.cancel(true)
        liveConversionExecutor.shutdown()
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
                if (::mainKeyboardContainer.isInitialized) {
                    mainKeyboardContainer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

    private fun getKeyboardHeightPx(): Int {
        if (::mainKeyboardContainer.isInitialized) {
            if (mainKeyboardContainer.height > 0) {
                return mainKeyboardContainer.height
            }
            if (mainKeyboardContainer.measuredHeight > 0) {
                return mainKeyboardContainer.measuredHeight
            }
            val padding = mainKeyboardContainer.paddingTop + mainKeyboardContainer.paddingBottom
            return getKeyButtonHeight() * 5 + padding
        }
        val padding = if (settings.keyboardLayout == "flick") 16 else 0
        return getKeyButtonHeight() * 5 + padding
    }


    private fun getSpaceString(): String = if (settings.spaceStyle == "full") "　" else " "

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

        val applyInsets: (View, WindowInsetsCompat) -> Unit = { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navInsets.bottom
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            applyInsets(view, insets)
            insets
        }

        rootLayout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.getRootWindowInsets(v)?.let { rootInsets ->
                    applyInsets(v, rootInsets)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        rootLayout.requestApplyInsets()

        // 1. 未確定テキスト表示エリア (ひらがな + ローマ字)
        composingTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数モード" else "かな: (なし)"
            textSize = 14f
            setPadding(dpToPx(16f), dpToPx(8f), dpToPx(16f), dpToPx(8f))
            setTextColor(colors.textColor)
        }
        rootLayout.addView(composingTextView)

        // 2. 変換候補バー (横スクロール可能) + 展開ボタン (▼)
        candidateBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.candidateBg)
            gravity = Gravity.CENTER_VERTICAL
        }

        candidateScrollContainer = HorizontalScrollView(this).apply {
            setBackgroundColor(colors.candidateBg)
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(2f))
        }
        candidateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
        candidateScrollContainer.addView(candidateLayout)

        candidateBarContainer.addView(
            candidateScrollContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        expandButton = Button(ContextThemeWrapper(this, R.style.Theme_Karukan)).apply {
            text = "▼"
            textSize = 14f
            isAllCaps = false
            minHeight = dpToPx(32f)
            minimumHeight = dpToPx(32f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
            backgroundTintList = ColorStateList.valueOf(colors.candidateBtnBg)
            setTextColor(colors.textColor)
            visibility = View.GONE
            setOnClickListener {
                triggerKeyFeedback()
                toggleCandidateExpand()
            }
        }
        val expandBtnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(4f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
        }
        candidateBarContainer.addView(expandButton, expandBtnParams)

        rootLayout.addView(candidateBarContainer)

        keyboardLayoutBuilder = KeyboardLayoutBuilder(
            context = this,
            isDarkThemeSupplier = { isDarkTheme() },
            flickSensitivitySupplier = { settings.flickSensitivity },
            keyButtonHeightSupplier = { getKeyButtonHeight() },
            isEnglishModeSupplier = { isEnglishMode },
            listener = keyboardEventListener
        )

        // 3. 画面キーボードの構築 (QWERTY または 12キー フリック)
        mainKeyboardContainer = if (settings.keyboardLayout == "flick") {
            keyboardLayoutBuilder.createFlickKeyboardContainer()
        } else {
            keyboardLayoutBuilder.createQwertyKeyboardContainer()
        }

        rootLayout.addView(mainKeyboardContainer)

        // 4. 複数行変換候補表示エリア (初期状態は非表示)
        candidateExpandedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.keyboardBg)
            visibility = View.GONE
        }

        val expandedHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.candidateBg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
        }

        expandedHeaderTitle = TextView(this).apply {
            text = "変換候補"
            textSize = 14f
            setTextColor(colors.textColor)
        }

        val collapseButton = Button(ContextThemeWrapper(this, R.style.Theme_Karukan)).apply {
            text = "たたむ ▲"
            textSize = 13f
            isAllCaps = false
            minHeight = dpToPx(32f)
            minimumHeight = dpToPx(32f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
            backgroundTintList = ColorStateList.valueOf(colors.candidateBtnBg)
            setTextColor(colors.textColor)
            setOnClickListener {
                triggerKeyFeedback()
                collapseCandidates()
            }
        }

        expandedHeaderLayout.addView(
            expandedHeaderTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        expandedHeaderLayout.addView(
            collapseButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        candidateExpandedContainer.addView(
            expandedHeaderLayout,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        expandedScrollView = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }

        candidateFlowLayout = CandidateFlowLayout(this).apply {
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }

        expandedScrollView.addView(
            candidateFlowLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        candidateExpandedContainer.addView(
            expandedScrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val initialKbHeight = getKeyboardHeightPx()
        rootLayout.addView(
            candidateExpandedContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (initialKbHeight > 0) initialKbHeight else LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 5. 絵文字パレット領域の構築 (初期状態は非表示)
        emojiPaletteController = EmojiPaletteController(
            context = this,
            engine = engine,
            isDarkThemeSupplier = { isDarkTheme() },
            keyboardHeightPxSupplier = { getKeyboardHeightPx() },
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

        override fun onCommit() {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            } else {
                val inputType = currentEditorInfo?.inputType ?: 0
                val isMultiline = (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                if (isMultiline) {
                    currentInputConnection?.commitText("\n", 1)
                } else {
                    val actionId = currentEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                    if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                        currentInputConnection?.performEditorAction(actionId)
                    } else {
                        currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
                    }
                }
            }
        }

        override fun onToggleEmojiPalette() {
            collapseCandidates()
            emojiPaletteController.toggle(mainKeyboardContainer)
        }

        override fun onToggleLanguageMode() {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            isEnglishMode = !isEnglishMode
            clearComposingState()
            if (isInputViewShown) {
                setInputView(onCreateInputView())
            }
        }

        override fun onDpadLeft() {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }

        override fun onDpadRight() {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }

        override fun onDpadUp() {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
        }

        override fun onDpadDown() {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
    }


    private fun onFlickInput(char: String) {
        if (char == "゛" || char == "゜" || char == "小") {
            // フリック特有の直前文字操作や変換ルールがあるため、
            // ひとまずバッファへそのまま追加してエンジンに委ねる
            romajiBuffer.append(char)
        } else {
            if (isEnglishMode) {
                currentInputConnection?.commitText(char, 1)
                return
            } else {
                romajiBuffer.append(char)
            }
        }
        updateComposingText()
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

        romajiBuffer.append(inputKey)
        updateComposingText()
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
            val selectedText = currentInputConnection?.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                currentInputConnection?.commitText("", 1)
            } else {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
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

        // 古い未完了の推論タスクがあれば即座にキャンセル（Native含む）して無駄なCPU消費を抑える
        if (currentLiveConversionQueryId > 0) {
            engine.cancelQuery(currentLiveConversionQueryId)
        }
        liveConversionFuture?.cancel(true)
        liveConversionRunnable?.let { mainHandler.removeCallbacks(it) }

        val queryId = ++currentLiveConversionQueryId
        val runnable = Runnable {
            liveConversionFuture = liveConversionExecutor.submit {
                val flushHiragana = engine.romajiToHiraganaFlush(raw)
                val candidates = fetchCandidates(flushHiragana)
                val topCandidate = candidates.firstOrNull() ?: flushHiragana

                if (Thread.currentThread().isInterrupted) return@submit

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
        liveConversionRunnable = runnable
        mainHandler.postDelayed(runnable, LIVE_CONVERSION_DEBOUNCE_MS)
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

        return if (settings.isKarukanEnabled) {
            engine.convert(hiragana, settings.numCandidates, fullContext)
        } else {
            listOf(hiragana)
        }
    }

    private fun performConversion() {
        if (isEnglishMode) return

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        val combinedCandidates = fetchCandidates(hiragana)

        renderCandidates(hiragana, combinedCandidates)
    }

    private fun toggleCandidateExpand() {
        if (isCandidateExpanded) {
            collapseCandidates()
        } else {
            expandCandidates()
        }
    }

    private fun expandCandidates() {
        if (isCandidateExpanded) return
        isCandidateExpanded = true

        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            emojiPaletteController.hide(mainKeyboardContainer)
        }

        val kbHeight = getKeyboardHeightPx()
        if (kbHeight > 0 && ::candidateExpandedContainer.isInitialized) {
            val params = candidateExpandedContainer.layoutParams
            params.height = kbHeight
            candidateExpandedContainer.layoutParams = params
        }

        if (::mainKeyboardContainer.isInitialized) {
            mainKeyboardContainer.visibility = View.GONE
        }
        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.visibility = View.VISIBLE
        }
        if (::expandButton.isInitialized) {
            expandButton.text = "▲"
        }
        if (::expandedScrollView.isInitialized) {
            expandedScrollView.scrollTo(0, 0)
        }
    }

    private fun collapseCandidates() {
        if (!isCandidateExpanded) return
        isCandidateExpanded = false

        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.visibility = View.GONE
        }
        if (::mainKeyboardContainer.isInitialized) {
            mainKeyboardContainer.visibility = View.VISIBLE
        }
        if (::expandButton.isInitialized) {
            expandButton.text = "▼"
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createCandidateButton(textValue: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        val cornerRadius = 8 * density
        val colors = getThemeColors()

        val normalColor = colors.candidateBtnBg
        val pressedColor = if (isDarkTheme()) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0")

        val stateListDrawable = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(pressedColor)
                    this.cornerRadius = cornerRadius
                }
            )
            addState(
                StateSet.WILD_CARD,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(normalColor)
                    this.cornerRadius = cornerRadius
                }
            )
        }

        return Button(ContextThemeWrapper(this, R.style.Theme_Karukan)).apply {
            text = textValue
            textSize = 16f
            isAllCaps = false
            minHeight = (36 * density).toInt()
            minimumHeight = (36 * density).toInt()
            minWidth = 0
            minimumWidth = 0
            setPadding(padH, padV, padH, padV)
            background = stateListDrawable
            setTextColor(colors.textColor)
            setOnClickListener {
                triggerKeyFeedback()
                onClick()
            }
        }
    }

    private fun renderCandidates(hiragana: String, candidates: List<String>) {
        candidateLayout.removeAllViews()
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }

        val allCandidates = mutableListOf<String>()
        if (candidates.isNotEmpty()) {
            allCandidates.addAll(candidates)
        }
        if (!allCandidates.contains(hiragana)) {
            allCandidates.add(hiragana)
        }

        if (allCandidates.isNotEmpty()) {
            if (::expandButton.isInitialized) {
                expandButton.visibility = View.VISIBLE
            }
            if (::expandedHeaderTitle.isInitialized) {
                expandedHeaderTitle.text = "変換候補 (${allCandidates.size}件)"
            }
        } else {
            if (::expandButton.isInitialized) {
                expandButton.visibility = View.GONE
            }
        }

        val btnMarginPx = dpToPx(3f)
        for (cand in allCandidates) {
            val candBtnSingle = createCandidateButton(cand) {
                onCandidateSelected(hiragana, cand)
            }
            val singleParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(btnMarginPx, btnMarginPx, btnMarginPx, btnMarginPx)
            }
            candidateLayout.addView(candBtnSingle, singleParams)

            if (::candidateFlowLayout.isInitialized) {
                val candBtnMulti = createCandidateButton(cand) {
                    onCandidateSelected(hiragana, cand)
                }
                candidateFlowLayout.addView(candBtnMulti)
            }
        }
    }

    private fun onCandidateSelected(hiragana: String, cand: String) {
        val pureCand = cand.extractPureCandidate()
        if (settings.isLearningEnabled) {
            engine.recordLearning(hiragana, pureCand)
        }
        currentInputConnection?.commitText(pureCand, 1)
        clearComposingState()
    }

    private fun String.extractPureCandidate(): String {
        val idx = this.indexOf(" (")
        return if (idx > 0) this.substring(0, idx) else this
    }

    private fun clearCandidateBar() {
        candidateLayout.removeAllViews()
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = View.GONE
        }
        if (isCandidateExpanded) {
            collapseCandidates()
        }
        val colors = getThemeColors()
        val defaultCandidateTextView = TextView(this).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(12, 8, 12, 8)
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
    }

    private fun clearComposingState() {
        if (currentLiveConversionQueryId > 0) {
            engine.cancelQuery(currentLiveConversionQueryId)
        }
        liveConversionFuture?.cancel(true)
        liveConversionRunnable?.let { mainHandler.removeCallbacks(it) }
        romajiBuffer.clear()
        currentLiveCandidate = null
        currentInputConnection?.finishComposingText()
        updateComposingText()
        clearCandidateBar()
    }

    private fun commitCurrentHiragana() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiragana = engine.romajiToHiraganaFlush(raw)
        val commitTextValue = (currentLiveCandidate ?: hiragana).extractPureCandidate()

        if (settings.isLearningEnabled && commitTextValue != hiragana) {
            engine.recordLearning(hiragana, commitTextValue)
        }

        currentInputConnection?.commitText(commitTextValue, 1)
        clearComposingState()
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        currentEditorInfo = attribute
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
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
        if (isCandidateExpanded && keyCode == KeyEvent.KEYCODE_BACK) {
            collapseCandidates()
            return true
        }

        if (event != null && event.isPrintingKey) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0) {
                val c = unicodeChar.toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c == '-') {
                    onKeyPress(c.lowercaseChar().toString())
                    return true
                } else if (c.code in 33..126) {
                    onKeyPress(c.toString())
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
