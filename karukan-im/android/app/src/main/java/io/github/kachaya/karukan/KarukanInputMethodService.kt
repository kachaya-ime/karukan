package io.github.kachaya.karukan

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.StateSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY & ニコタッチ 12キー入力対応)
 */
class KarukanInputMethodService : InputMethodService() {

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    // 直前確定記憶 (確定取り消し用)
    private var lastCommittedSurface: String = ""
    private var lastCommittedReading: String = ""

    private lateinit var karukanInputView: KarukanInputView
    private lateinit var composingTextView: TextView

    // カーソル位置情報 (物理キーボード接続時用)
    private var cursorX: Float = Float.NaN
    private var cursorYTop: Float = Float.NaN
    private var cursorYBottom: Float = Float.NaN
    private var isCursorAnchorValid: Boolean = false

    private lateinit var preferencesManager: PreferencesManager
    private val settings: KarukanSettings
        get() = preferencesManager.currentSettings

    // モード管理 (かな / 英数 / カタカナ / 候補選択 / ソース絞り込み)
    enum class SourceFilter {
        ALL,
        LEARNING,
        DICTIONARY,
        AI,
        SYMBOL
    }

    private var inputMode: InputMode = InputMode.HIRAGANA
    val isEnglishMode: Boolean
        get() = inputMode != InputMode.HIRAGANA
    private var isKatakanaMode: Boolean = false
    private var activeSourceFilter: SourceFilter = SourceFilter.ALL
    private var selectedCandidateIndex: Int = -1
    private var currentCandidates: List<String> = emptyList()
    private var showCandidateDetails: Boolean = false
    private var isLiveConversionPreviewActive: Boolean = false

    // エディタ情報管理
    private var currentEditorInfo: EditorInfo? = null
    private var pendingInputViewReload: Boolean = false

    // 絵文字パレット・画面切り替え管理
    private lateinit var mainKeyboardContainer: View
    private lateinit var keyboardLayoutBuilder: KeyboardLayoutBuilder

    // ライブ変換用 Coroutines スコープと状態
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveConversionJob: Job? = null
    private var currentLiveConversionQueryId = 0L
    private var currentLiveCandidate: String? = null

    private var audioManager: AudioManager? = null

    companion object {
        private const val TAG = "KarukanInputService"
        private const val LIVE_CONVERSION_DEBOUNCE_MS = 100L
    }

    override fun onCreate() {
        setTheme(R.style.Theme_Karukan)
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        engine = KarukanEngine(this)

        preferencesManager = PreferencesManager(this) { updatedSettings ->
            if (::engine.isInitialized) {
                engine.updateEngineConfig(updatedSettings)
            }
            serviceScope.launch {
                if (isInputViewShown) {
                    setInputView(onCreateInputView())
                    pendingInputViewReload = false
                } else {
                    pendingInputViewReload = true
                }
            }
        }
        preferencesManager.startListening()

        if (::engine.isInitialized) {
            engine.updateEngineConfig(settings)
        }
    }

    private val inputViewListener = object : KarukanInputView.Listener {
        override fun isDarkTheme(): Boolean = this@KarukanInputMethodService.isDarkTheme()
        override fun isHardKeyboardConnected(): Boolean = this@KarukanInputMethodService.isHardKeyboardConnected()
        override fun is12KeyHardKeyboardConnected(): Boolean = this@KarukanInputMethodService.is12KeyHardKeyboardConnected()
        override fun getKeyboardHeightPx(): Int = this@KarukanInputMethodService.getKeyboardHeightPx()
        override fun triggerKeyFeedback() = this@KarukanInputMethodService.triggerKeyFeedback()
        override fun onCandidateSelected(hiragana: String, candidate: String, index: Int) {
            this@KarukanInputMethodService.selectedCandidateIndex = index
            this@KarukanInputMethodService.onCandidateSelected(hiragana, candidate)
        }
        override fun onEmojiSelected(emoji: String) {
            currentInputConnection?.commitText(emoji, 1)
        }
        override fun getKeyboardEventListener(): KeyboardLayoutBuilder.KeyboardEventListener = keyboardEventListener
        override fun getSourceFilterLabel(filter: SourceFilter): String = this@KarukanInputMethodService.getSourceFilterLabel(filter)
        override fun getActiveSourceFilter(): SourceFilter = activeSourceFilter
        override fun getEngine(): KarukanEngine = engine
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissFloatingCandidates()
        cancelLiveConversion()
        serviceScope.cancel()
        preferencesManager.stopListening()
    }

    private fun cancelLiveConversion() {
        liveConversionJob?.cancel()
        liveConversionJob = null
        if (currentLiveConversionQueryId > 0) {
            engine.cancelQuery(currentLiveConversionQueryId)
        }
    }

    private fun requestCursorUpdates() {
        val ic = currentInputConnection ?: return
        try {
            ic.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request cursor updates", e)
        }
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (cursorAnchorInfo == null) {
            isCursorAnchorValid = false
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateCursorPosition(false, Float.NaN, Float.NaN, Float.NaN)
            }
            return
        }
        val matrix = cursorAnchorInfo.matrix
        var horizontal = cursorAnchorInfo.insertionMarkerHorizontal
        var top = cursorAnchorInfo.insertionMarkerTop
        var bottom = cursorAnchorInfo.insertionMarkerBottom

        val composingStart = cursorAnchorInfo.composingTextStart
        if (composingStart >= 0) {
            val bounds = cursorAnchorInfo.getCharacterBounds(composingStart)
            if (bounds != null) {
                if (horizontal.isNaN()) horizontal = bounds.left
                if (top.isNaN()) top = bounds.top
                if (bottom.isNaN()) bottom = bounds.bottom
            }
        }

        if (!horizontal.isNaN() && !bottom.isNaN()) {
            val ptsBottom = floatArrayOf(horizontal, bottom)
            val ptsTop = floatArrayOf(horizontal, if (!top.isNaN()) top else bottom)
            matrix.mapPoints(ptsBottom)
            matrix.mapPoints(ptsTop)

            cursorX = ptsBottom[0]
            cursorYBottom = ptsBottom[1]
            cursorYTop = ptsTop[1]
            isCursorAnchorValid = true

            if (::karukanInputView.isInitialized) {
                karukanInputView.updateCursorPosition(true, cursorX, cursorYBottom, cursorYTop)
                if (isHardKeyboardConnected()) {
                    karukanInputView.updateFloatingOverlay(selectedCandidateIndex)
                }
            }
        } else {
            isCursorAnchorValid = false
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateCursorPosition(false, Float.NaN, Float.NaN, Float.NaN)
            }
        }
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
                if (::karukanInputView.isInitialized) {
                    karukanInputView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else if (::mainKeyboardContainer.isInitialized) {
                    mainKeyboardContainer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibration feedback failed", e)
            }
        }

        if (settings.isSoundEnabled) {
            try {
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
        }
        return getKeyButtonHeight() * 5
    }

    private var currentEffectiveKeyboardLayout: String? = null

    private fun getEffectiveKeyboardLayout(): String {
        return if (is12KeyHardKeyboardConnected()) "niko" else settings.keyboardLayout
    }

    private fun getSpaceString(): String = if (settings.spaceStyle == "full") "　" else " "

    override fun onCreateInputView(): View {
        val effectiveLayout = getEffectiveKeyboardLayout()
        currentEffectiveKeyboardLayout = effectiveLayout

        keyboardLayoutBuilder = KeyboardLayoutBuilder(
            context = this,
            isDarkThemeSupplier = { isDarkTheme() },
            keyButtonHeightSupplier = { getKeyButtonHeight() },
            isEnglishModeSupplier = { isEnglishMode },
            inputModeSupplier = { inputMode },
            listener = keyboardEventListener
        )

        karukanInputView = KarukanInputView(this).apply {
            initView(
                listener = inputViewListener,
                keyboardLayoutBuilder = keyboardLayoutBuilder,
                keyboardLayoutSetting = effectiveLayout,
                isEnglishMode = isEnglishMode
            )
            updateInputMode(inputMode)
        }
        composingTextView = karukanInputView.composingTextView
        mainKeyboardContainer = karukanInputView.mainKeyboardContainer

        return karukanInputView
    }

    private fun getSourceFilterLabel(filter: SourceFilter): String = when (filter) {
        SourceFilter.ALL -> "全体"
        SourceFilter.LEARNING -> "📝学習"
        SourceFilter.DICTIONARY -> "📚辞書"
        SourceFilter.AI -> "🤖AI"
        SourceFilter.SYMBOL -> "🔄記号"
    }

    private val keyboardEventListener = object : KeyboardLayoutBuilder.KeyboardEventListener {
        override fun onKeyFeedback() = triggerKeyFeedback()
        override fun onKeyPress(key: String, isShift: Boolean) { executeKeyAction(KeyAction.InputChar(key, isShift)) }
        override fun onNikoTouchInput(char: String) { executeKeyAction(KeyAction.NikoTouchInput(char)) }
        override fun onNikoTouchToggle() { executeKeyAction(KeyAction.NikoTouchToggle) }
        override fun onBackspace() { executeKeyAction(KeyAction.Backspace) }
        override fun onDelete() { executeKeyAction(KeyAction.DeleteForward) }
        override fun onCtrlBackspace() { executeKeyAction(KeyAction.DeleteSelectedCandidate) }
        override fun onSpaceOrConvert() { executeKeyAction(KeyAction.SpaceOrConvert) }
        override fun onCommit() { executeKeyAction(KeyAction.Commit()) }
        override fun onToggleEmojiPalette() { executeKeyAction(KeyAction.ToggleEmojiPalette) }
        override fun onToggleSymbolPalette() { executeKeyAction(KeyAction.ToggleSymbolPalette) }
        override fun onToggleLanguageMode() { executeKeyAction(KeyAction.ToggleLanguageMode) }
        override fun onNikoToggleLanguageMode() {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            inputMode = when (inputMode) {
                InputMode.NUMERIC -> InputMode.HIRAGANA
                else -> InputMode.NUMERIC
            }
            clearComposingState()
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateInputMode(inputMode)
            }
        }
        override fun onFixChunk() { executeKeyAction(KeyAction.FixChunk) }
        override fun onDpadLeft() { executeKeyAction(KeyAction.MoveLeft()) }
        override fun onDpadRight() { executeKeyAction(KeyAction.MoveRight()) }
        override fun onDpadUp() { executeKeyAction(KeyAction.MoveUp()) }
        override fun onDpadDown() { executeKeyAction(KeyAction.MoveDown()) }
        override fun onCtrlShortcut(key: String) { executeKeyAction(KeyAction.CtrlShortcut(key)) }
        override fun onEsc() { executeKeyAction(KeyAction.Escape) }
        override fun onTab() { executeKeyAction(KeyAction.Tab()) }
        override fun onKatakana() { executeKeyAction(KeyAction.CommitKatakana) }
    }

    fun executeKeyAction(action: KeyAction, isPhysicalKeyboard: Boolean = false): Boolean {
        when (action) {
            is KeyAction.InputChar -> onKeyPress(action.text, action.isShift)
            is KeyAction.NikoTouchInput -> onNikoTouchInput(action.char)
            KeyAction.NikoTouchToggle -> onNikoTouchToggle()
            KeyAction.Backspace -> onBackspace()
            KeyAction.DeleteForward -> onDeleteForward()
            KeyAction.DeleteSelectedCandidate -> onDeleteSelectedCandidate()
            KeyAction.SpaceOrConvert -> onSpaceKey()
            KeyAction.FullWidthSpace -> onFullWidthSpace()
            is KeyAction.Commit -> {
                if (isPhysicalKeyboard && romajiBuffer.isEmpty() && selectedCandidateIndex < 0) {
                    return false
                }
                onCommitKey()
            }
            KeyAction.Escape -> onEscape()
            is KeyAction.Tab -> onTabKey(action.isShift)
            KeyAction.CommitHiragana -> {
                if (romajiBuffer.isEmpty()) return false
                commitCurrentHiragana()
            }
            KeyAction.CommitKatakana -> {
                if (isPhysicalKeyboard && romajiBuffer.isEmpty()) return false
                commitKatakanaOrToggleMode()
            }
            KeyAction.CommitHankakuKatakana -> {
                if (romajiBuffer.isEmpty()) return false
                commitHankakuKatakana()
            }
            KeyAction.CommitZenkakuAlphanumeric -> {
                if (romajiBuffer.isEmpty()) return false
                commitZenkakuAlphanumeric()
            }
            KeyAction.CommitHankakuAlphanumeric -> {
                if (romajiBuffer.isEmpty()) return false
                commitHankakuAlphanumeric()
            }
            KeyAction.ToggleLanguageMode -> onToggleLanguageMode()
            KeyAction.FixChunk -> fixChunkPreedit()
            KeyAction.KatakanaHiraganaToggle -> {
                isKatakanaMode = !isKatakanaMode
                updateComposingText()
            }
            KeyAction.ToggleEmojiPalette -> {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleEmojiPalette()
                }
            }
            KeyAction.ToggleSymbolPalette -> {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleSymbolPalette()
                }
            }
            KeyAction.ToggleDetailDisplay -> onToggleDetailDisplay()
            KeyAction.ToggleLiveConversion -> onToggleLiveConversion()
            KeyAction.ToggleCandidateExpand -> {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleCandidateExpand()
                }
            }
            KeyAction.Reconvert -> reconvert()
            KeyAction.SelectAll -> selectAllEditingArea()
            is KeyAction.SelectCandidateNumber -> {
                if (currentCandidates.isNotEmpty() && action.index in currentCandidates.indices) {
                    val cand = currentCandidates[action.index]
                    val hiragana = engine.romajiToHiraganaFlush(romajiBuffer.toString())
                    onCandidateSelected(hiragana, cand)
                } else return false
            }
            is KeyAction.MoveLeft -> onDpadLeft(action.isShift)
            is KeyAction.MoveRight -> onDpadRight(action.isShift)
            is KeyAction.MoveUp -> if (selectedCandidateIndex >= 0) onPreviousCandidate() else onDpadUp(action.isShift)
            is KeyAction.MoveDown -> if (selectedCandidateIndex >= 0) onNextCandidate() else onDpadDown(action.isShift)
            KeyAction.MoveHome -> moveCursorToStartOfLine()
            KeyAction.MoveEnd -> moveCursorToEndOfLine()
            KeyAction.PageUp -> onPageUp()
            KeyAction.PageDown -> onPageDown()
            is KeyAction.CtrlShortcut -> {
                if (!handleCtrlShortcut(action.key)) return false
            }
        }
        return true
    }

    private fun onToggleLanguageMode() {
        if (romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
        val isEng = inputMode != InputMode.HIRAGANA
        inputMode = if (isEng) InputMode.HIRAGANA else InputMode.ALPHANUMERIC
        clearComposingState()
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateInputMode(inputMode)
        }
    }

    private fun onDpadLeft(isShift: Boolean = false) {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        }
        val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
        val eventTime = SystemClock.uptimeMillis()
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, metaState))
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0, metaState))
    }

    private fun onDpadRight(isShift: Boolean = false) {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        }
        val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
        val eventTime = SystemClock.uptimeMillis()
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, metaState))
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, metaState))
    }

    private fun onNikoTouchInput(char: String) {
        if (isEnglishMode || NikoKeyboardLayout.isDirectCommit(char)) {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            currentInputConnection?.commitText(char, 1)
            return
        }
        romajiBuffer.append(char)
        updateComposingText()
    }

    private fun onNikoTouchToggle() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (textBefore.isNotEmpty()) {
            val c = textBefore[0]
            val toggled = when {
                c in 'a'..'z' -> c.uppercaseChar().toString()
                c in 'A'..'Z' -> c.lowercaseChar().toString()
                else -> null
            }
            if (toggled != null) {
                currentInputConnection?.deleteSurroundingText(1, 0)
                currentInputConnection?.commitText(toggled, 1)
                return
            }
        }

        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            if (hiragana.isNotEmpty()) {
                val lastChar = hiragana.last().toString()
                val toggled = NikoKeyboardLayout.toggleChar(lastChar)
                if (lastChar != toggled) {
                    val prefix = hiragana.substring(0, hiragana.length - 1)
                    romajiBuffer.clear()
                    romajiBuffer.append(prefix).append(toggled)
                    updateComposingText()
                }
            }
        }
    }

    private fun onKeyPress(key: String, isShift: Boolean = false) {
        var inputKey = key
        if (isEnglishMode) {
            if (settings.isAutoCapitalizationEnabled && isFirstCharOfSentence()) {
                inputKey = inputKey.uppercase(Locale.getDefault())
            }
            currentInputConnection?.commitText(inputKey, 1)
            return
        }

        // Shift + 英字 (かなモード中) -> 英数字モード切替 + 大文字入力
        if (isShift && inputKey.length == 1 && inputKey[0] in 'A'..'Z') {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            inputMode = InputMode.ALPHANUMERIC
            currentInputConnection?.commitText(inputKey.uppercase(Locale.getDefault()), 1)
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateInputMode(inputMode)
            }
            return
        }

        if (selectedCandidateIndex >= 0) {
            // 読みを伸ばしてインクリメンタルに変換を更新
            selectedCandidateIndex = -1
        }

        romajiBuffer.append(inputKey)
        updateComposingText()
    }

    private fun isFirstCharOfSentence(): Boolean {
        val textBefore = currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() ?: ""
        return textBefore.isEmpty() || textBefore.endsWith(". ") || textBefore.endsWith("\n")
    }

    private fun onBackspace() {
        if (selectedCandidateIndex >= 0) {
            selectedCandidateIndex = -1
            collapseCandidates()
            updateComposingText()
            return
        }
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

    private fun onDeleteForward() {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        } else if (romajiBuffer.isNotEmpty()) {
            onBackspace()
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL))
        }
    }

    private fun updateComposingText() {
        requestCursorUpdates()
        val hasInput = romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateNikoInputState(hasInput)
        }
        if (isEnglishMode) {
            composingTextView.text = "英数モード"
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) {
            val katakanaTag = if (isKatakanaMode) " [カタカナ]" else ""
            composingTextView.text = "かな: (なし)$katakanaTag"
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            clearCandidateBar()
            return
        }

        // 絵文字モード (:smile など)
        if (raw.startsWith(":")) {
            val emojiQuery = raw.substring(1)
            val emojiCandidates = fetchEmojiCandidates(emojiQuery)
            currentCandidates = emojiCandidates
            composingTextView.text = "絵文字検索: $raw"
            currentInputConnection?.setComposingText(raw, 1)
            renderCandidates(raw, emojiCandidates)
            return
        }

        val hiraganaRaw = engine.romajiToHiragana(raw)
        val hiragana = if (isKatakanaMode) engine.hiraganaToKatakana(hiraganaRaw) else hiraganaRaw

        val katakanaTag = if (isKatakanaMode) " [カタカナ]" else ""
        if (!settings.isLiveConversionEnabled) {
            currentLiveCandidate = hiragana
            composingTextView.text = "かな: $hiragana  [$raw]$katakanaTag"
            currentInputConnection?.setComposingText(hiragana, 1)
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateFloatingOverlay(selectedCandidateIndex)
            }
            return
        }

        // ライブ変換
        isLiveConversionPreviewActive = true
        composingTextView.text = "かな: $hiragana  [$raw]$katakanaTag"
        currentInputConnection?.setComposingText(hiragana, 1)
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateFloatingOverlay(selectedCandidateIndex)
        }

        cancelLiveConversion()

        val queryId = ++currentLiveConversionQueryId

        liveConversionJob = serviceScope.launch {
            delay(LIVE_CONVERSION_DEBOUNCE_MS)
            if (!isActive || queryId != currentLiveConversionQueryId) return@launch

            val (flushHiragana, candidates, topCandidate) = withContext(Dispatchers.Default) {
                if (!coroutineContext.isActive || queryId != currentLiveConversionQueryId) {
                    return@withContext Triple("", emptyList(), "")
                }
                val flushHiraganaRaw = engine.romajiToHiraganaFlush(raw)
                val flushHiragana = if (isKatakanaMode) engine.hiraganaToKatakana(flushHiraganaRaw) else flushHiraganaRaw
                val candidates = fetchCandidates(flushHiragana, queryId)
                val topCandidate = candidates.firstOrNull() ?: flushHiragana
                Triple(flushHiragana, candidates, topCandidate)
            }

            if (isActive && queryId == currentLiveConversionQueryId && romajiBuffer.toString() == raw) {
                currentLiveCandidate = topCandidate
                val detailStr = if (showCandidateDetails) " (推論: Live, 候補数: ${candidates.size})" else ""
                composingTextView.text = "かな: $flushHiragana → $topCandidate  [$raw]$katakanaTag$detailStr"
                currentInputConnection?.setComposingText(topCandidate, 1)
                renderCandidates(flushHiragana, candidates)
            }
        }
    }

    private fun fetchEmojiCandidates(query: String): List<String> {
        val result = mutableListOf<String>()
        val categories = engine.getEmojiCategories()
        for ((_, emojis) in categories) {
            for (e in emojis) {
                if (query.isEmpty() || e.contains(query)) {
                    result.add(e)
                }
            }
        }
        return if (result.isNotEmpty()) result.distinct().take(30) else listOf("😊", "👍", "🎉", "❤️")
    }

    private fun fetchCandidates(hiragana: String, queryId: Long = 0): List<String> {
        if (hiragana.isEmpty()) return emptyList()

        var leftContext = ""
        if (settings.useContext) {
            val rawContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars + hiragana.length + 10, 0)?.toString() ?: ""
            val rawBuffer = romajiBuffer.toString()
            var cleanedContext = rawContext

            // 未確定文字列 (hiragana や入力バッファ) が文脈末尾に含まれている場合は慎重に除去
            if (hiragana.isNotEmpty() && cleanedContext.endsWith(hiragana)) {
                cleanedContext = cleanedContext.substring(0, cleanedContext.length - hiragana.length)
            } else if (rawBuffer.isNotEmpty() && cleanedContext.endsWith(rawBuffer)) {
                cleanedContext = cleanedContext.substring(0, cleanedContext.length - rawBuffer.length)
            }

            // 部分的なインクリメンタル入力の重複チェック（例: 「きょうはい」に対する「きょうは」の重複など）
            for (len in hiragana.length downTo 1) {
                if (cleanedContext.length >= len &&
                    cleanedContext.regionMatches(cleanedContext.length - len, hiragana, 0, len)
                ) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - len)
                    break
                }
            }

            leftContext = cleanedContext.trimEnd('\r', '\n')
            if (leftContext.length > settings.contextChars) {
                leftContext = leftContext.takeLast(settings.contextChars)
            }
        }
        val fullContext = if (settings.persona.isNotEmpty()) {
            if (leftContext.isNotEmpty()) "${settings.persona} $leftContext" else settings.persona
        } else {
            leftContext
        }

        return engine.convert(hiragana, settings.numCandidates, fullContext, queryId)
    }

    private fun filterCandidatesBySource(rawCandidates: List<String>): List<String> {
        if (activeSourceFilter == SourceFilter.ALL) return rawCandidates

        val learningEntries = try {
            engine.getLearningEntries().map { it.surface }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        return when (activeSourceFilter) {
            SourceFilter.LEARNING -> rawCandidates.filter { it in learningEntries }
            SourceFilter.SYMBOL -> rawCandidates.filter { cand ->
                cand.any { c ->
                    c in '0'..'9' || c in '①'..'⑨' || c in '一'..'九' ||
                    c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~' ||
                    Character.getType(c) == Character.OTHER_SYMBOL.toInt() ||
                    Character.getType(c) == Character.SURROGATE.toInt()
                } || cand.contains("年") || cand.contains("月") || cand.contains("日")
            }
            SourceFilter.AI -> rawCandidates.filter { it !in learningEntries }
            SourceFilter.DICTIONARY -> rawCandidates.filter { it.isNotEmpty() }
            SourceFilter.ALL -> rawCandidates
        }
    }

    private fun performConversion() {
        if (isEnglishMode) return

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiraganaRaw = engine.romajiToHiraganaFlush(raw)
        val hiragana = if (isKatakanaMode) engine.hiraganaToKatakana(hiraganaRaw) else hiraganaRaw
        val combinedCandidates = fetchCandidates(hiragana)

        renderCandidates(hiragana, combinedCandidates)
    }

    private fun renderCandidates(hiragana: String, rawCandidates: List<String>) {
        val filtered = filterCandidatesBySource(rawCandidates)
        val allCandidates = mutableListOf<String>()
        if (filtered.isNotEmpty()) {
            allCandidates.addAll(filtered)
        }
        if (!allCandidates.contains(hiragana) && activeSourceFilter == SourceFilter.ALL) {
            allCandidates.add(hiragana)
        }
        currentCandidates = allCandidates

        if (::karukanInputView.isInitialized) {
            karukanInputView.renderCandidates(hiragana, allCandidates, selectedCandidateIndex)
        }
    }

    private fun clearCandidateBar() {
        if (::karukanInputView.isInitialized) {
            karukanInputView.clearCandidateBar(isEnglishMode)
        }
    }

    private fun collapseCandidates() {
        if (::karukanInputView.isInitialized) {
            karukanInputView.collapseCandidates()
        }
    }

    private fun dismissFloatingCandidates() {
        if (::karukanInputView.isInitialized) {
            karukanInputView.dismissFloatingCandidates()
        }
    }

    private fun updateSelectionPreview() {
        if (selectedCandidateIndex in currentCandidates.indices) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            val selectedCand = currentCandidates[selectedCandidateIndex]
            val pureCand = selectedCand.extractPureCandidate()
            val filterTag = if (activeSourceFilter != SourceFilter.ALL) " [${getSourceFilterLabel(activeSourceFilter)}]" else ""
            composingTextView.text = "かな: $hiragana → $pureCand  [${selectedCandidateIndex + 1}/${currentCandidates.size}]$filterTag"
            currentInputConnection?.setComposingText(pureCand, 1)
            renderCandidates(hiragana, currentCandidates)
        }
    }

    private fun onNextCandidate() {
        if (currentCandidates.isEmpty()) {
            performConversion()
            return
        }
        selectedCandidateIndex = if (selectedCandidateIndex < 0) 0 else (selectedCandidateIndex + 1) % currentCandidates.size
        updateSelectionPreview()
    }

    private fun onPreviousCandidate() {
        if (currentCandidates.isEmpty()) return
        selectedCandidateIndex = if (selectedCandidateIndex <= 0) currentCandidates.size - 1 else selectedCandidateIndex - 1
        updateSelectionPreview()
    }

    private fun onSelectCandidateIndex(index: Int) {
        if (index in currentCandidates.indices) {
            selectedCandidateIndex = index
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            onCandidateSelected(hiragana, currentCandidates[index])
        }
    }

    private fun onSpaceKey() {
        if (isEnglishMode) {
            currentInputConnection?.commitText(getSpaceString(), 1)
            return
        }
        if (selectedCandidateIndex >= 0) {
            onNextCandidate()
        } else if (romajiBuffer.isNotEmpty()) {
            performConversion()
            if (currentCandidates.isNotEmpty()) {
                selectedCandidateIndex = 0
                updateSelectionPreview()
            }
        } else {
            currentInputConnection?.commitText(getSpaceString(), 1)
        }
    }

    private fun onFullWidthSpace() {
        if (selectedCandidateIndex >= 0) {
            onPreviousCandidate()
        } else {
            currentInputConnection?.commitText("　", 1)
        }
    }

    private fun onCommitKey() {
        if (selectedCandidateIndex in currentCandidates.indices) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            onCandidateSelected(hiragana, currentCandidates[selectedCandidateIndex])
        } else if (romajiBuffer.isNotEmpty()) {
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

    private fun onEscape() {
        if (selectedCandidateIndex >= 0 || (::karukanInputView.isInitialized && karukanInputView.isCandidateExpanded)) {
            selectedCandidateIndex = -1
            collapseCandidates()
            updateComposingText()
        } else if (isLiveConversionPreviewActive) {
            isLiveConversionPreviewActive = false
            currentLiveCandidate = null
            updateComposingText()
        } else if (romajiBuffer.isNotEmpty()) {
            clearComposingState()
        }
    }

    private fun onTabKey(isShift: Boolean = false) {
        if (selectedCandidateIndex >= 0) {
            if (isShift) onPreviousCandidate() else onNextCandidate()
        } else if (romajiBuffer.isNotEmpty()) {
            performConversionNoLearning()
        } else {
            val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
            val eventTime = SystemClock.uptimeMillis()
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, metaState))
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, metaState))
        }
    }

    private fun performConversionNoLearning() {
        if (isEnglishMode || romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val hiragana = engine.romajiToHiraganaFlush(raw)
        val candidates = engine.convert(hiragana, settings.numCandidates, "")
        selectedCandidateIndex = 0
        renderCandidates(hiragana, candidates)
        updateSelectionPreview()
    }

    private fun commitKatakanaOrToggleMode() {
        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            val katakana = engine.hiraganaToKatakana(hiragana)
            currentInputConnection?.commitText(katakana, 1)
            clearComposingState()
        } else {
            isKatakanaMode = !isKatakanaMode
            updateComposingText()
        }
    }

    private fun fixChunkPreedit() {
        if (romajiBuffer.isEmpty()) return

        val res = engine.processKey('j'.code, 4)
        if (res.consumed && res.hasPreedit && !res.preeditText.isNullOrEmpty()) {
            romajiBuffer.clear()
            romajiBuffer.append(res.preeditText)
        } else {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            romajiBuffer.clear()
            romajiBuffer.append(hiragana)
        }
        updateComposingText()
    }

    private fun cycleSourceFilterNext() {
        activeSourceFilter = when (activeSourceFilter) {
            SourceFilter.ALL -> SourceFilter.LEARNING
            SourceFilter.LEARNING -> SourceFilter.DICTIONARY
            SourceFilter.DICTIONARY -> SourceFilter.AI
            SourceFilter.AI -> SourceFilter.SYMBOL
            SourceFilter.SYMBOL -> SourceFilter.ALL
        }
        performConversion()
    }

    private fun cycleSourceFilterPrevious() {
        activeSourceFilter = when (activeSourceFilter) {
            SourceFilter.ALL -> SourceFilter.SYMBOL
            SourceFilter.SYMBOL -> SourceFilter.LEARNING
            SourceFilter.LEARNING -> SourceFilter.DICTIONARY
            SourceFilter.DICTIONARY -> SourceFilter.AI
            SourceFilter.AI -> SourceFilter.ALL
        }
        performConversion()
    }

    private fun filterByAiSource() {
        activeSourceFilter = SourceFilter.AI
        performConversion()
    }

    private fun onDeleteSelectedCandidate() {
        if (selectedCandidateIndex in currentCandidates.indices) {
            val pureCand = currentCandidates[selectedCandidateIndex].extractPureCandidate()
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            engine.removeLearningEntry(hiragana, pureCand)
            performConversion()
        }
    }

    private fun selectAllEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun moveCursorToStartOfLine() {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME))
    }

    private fun moveCursorToEndOfLine() {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_END))
    }

    private fun onPageUp() {
        if (currentCandidates.isNotEmpty()) {
            selectedCandidateIndex = maxOf(0, selectedCandidateIndex - 5)
            updateSelectionPreview()
        }
    }

    private fun onPageDown() {
        if (currentCandidates.isNotEmpty()) {
            selectedCandidateIndex = minOf(currentCandidates.size - 1, if (selectedCandidateIndex < 0) 4 else selectedCandidateIndex + 5)
            updateSelectionPreview()
        }
    }

    private fun commitHankakuKatakana() {
        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            val katakana = engine.hiraganaToKatakana(hiragana)
            val hankakuKatakana = toHankakuKatakana(katakana)
            currentInputConnection?.commitText(hankakuKatakana, 1)
            clearComposingState()
        }
    }

    private fun commitZenkakuAlphanumeric() {
        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            val zenkakuAlpha = toZenkakuAlphanumeric(raw)
            currentInputConnection?.commitText(zenkakuAlpha, 1)
            clearComposingState()
        }
    }

    private fun commitHankakuAlphanumeric() {
        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            currentInputConnection?.commitText(raw, 1)
            clearComposingState()
        }
    }

    private fun toHankakuKatakana(str: String): String {
        val zenkaku = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンァィゥェォッャュョーガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
        val hankaku = arrayOf(
            "ｱ","ｲ","ｳ","ｴ","ｵ","ｶ","ｷ","ｸ","ｹ","ｺ","ｻ","ｼ","ｽ","ｾ","ｿ","ﾀ","ﾁ","ﾂ","ﾃ","ﾄ","ﾅ","ﾆ","ﾇ","ﾈ","ﾉ","ﾊ","ﾋ","ﾌ","ﾍ","ﾎ","ﾏ","ﾐ","ﾑ","ﾒ","ﾓ","ﾔ","ﾕ","ﾖ","ﾗ","ﾘ","ﾙ","ﾚ","ﾛ","ﾜ","ｦ","ﾝ","ｧ","ｨ","ｩ","ｪ","ｫ","ｯ","ｬ","ｭ","ｮ","ｰ",
            "ｶﾞ","ｷﾞ","ｸﾞ","ｹﾞ","ｺﾞ","ｻﾞ","ｼﾞ","ｽﾞ","ｾﾞ","ｿﾞ","ﾀﾞ","ﾁﾞ","ﾂﾞ","ﾃﾞ","ﾄﾞ","ﾊﾞ","ﾋﾞ","ﾌﾞ","ﾍﾞ","ﾎﾞ","ﾊﾟ","ﾋﾟ","ﾌﾟ","ﾍﾟ","ﾎﾟ"
        )
        val sb = StringBuilder()
        for (c in str) {
            val idx = zenkaku.indexOf(c)
            if (idx != -1) {
                sb.append(hankaku[idx])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun toZenkakuAlphanumeric(str: String): String {
        val sb = StringBuilder()
        for (c in str) {
            val code = c.code
            if (code in 0x0021..0x007E) {
                sb.append((code + 0xFEE0).toChar())
            } else if (code == 0x0020) {
                sb.append('　')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun reconvert(): Boolean {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) return false
        if (lastCommittedSurface.isEmpty() || lastCommittedReading.isEmpty()) return false

        val surfaceLength = lastCommittedSurface.length
        currentInputConnection?.deleteSurroundingText(surfaceLength, 0)

        val readingToRestore = lastCommittedReading
        lastCommittedSurface = ""
        lastCommittedReading = ""

        romajiBuffer.append(readingToRestore)
        updateComposingText()
        return true
    }

    private class CtrlCommand(
        val requiresComposing: Boolean = false,
        val requiresNonComposing: Boolean = false,
        val action: () -> Boolean
    )

    private val ctrlShortcutCommands: Map<String, CtrlCommand> by lazy {
        mapOf(
            "d" to CtrlCommand {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleCandidateExpand()
                    true
                } else false
            },
            "s" to CtrlCommand(requiresNonComposing = true) { reconvert() },
            "w" to CtrlCommand(requiresComposing = true) { cycleSourceFilterNext(); true },
            "q" to CtrlCommand(requiresComposing = true) { activeSourceFilter = SourceFilter.SYMBOL; performConversion(); true },
            "i" to CtrlCommand { filterByAiSource(); true },
            "t" to CtrlCommand { activeSourceFilter = SourceFilter.LEARNING; performConversion(); true },
            "r" to CtrlCommand { activeSourceFilter = SourceFilter.SYMBOL; performConversion(); true },
            "k" to CtrlCommand { commitKatakanaOrToggleMode(); true },
            "j" to CtrlCommand(requiresComposing = true) { fixChunkPreedit(); true },
            "a" to CtrlCommand { selectAllEditingArea(); true },
            "h" to CtrlCommand { onBackspace(); true },
            "m" to CtrlCommand { onCommitKey(); true },
            "g" to CtrlCommand { onEscape(); true },
            "l" to CtrlCommand { onToggleLiveConversion(); true },
            "y" to CtrlCommand { onToggleDetailDisplay(); true },
            "n" to CtrlCommand(requiresComposing = true) { onNextCandidate(); true },
            "p" to CtrlCommand(requiresComposing = true) { onPreviousCandidate(); true },
            "f" to CtrlCommand(requiresComposing = true) { onDpadRight(); true },
            "b" to CtrlCommand(requiresComposing = true) { onDpadLeft(); true },
            "e" to CtrlCommand(requiresComposing = true) { moveCursorToEndOfLine(); true }
        )
    }

    private fun handleCtrlShortcut(key: String): Boolean {
        val lowerKey = key.lowercase(Locale.getDefault())
        val command = ctrlShortcutCommands[lowerKey] ?: return false
        val isComposing = romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0

        if (command.requiresComposing && !isComposing) return false
        if (command.requiresNonComposing && isComposing) return false

        return command.action()
    }

    private fun onToggleLiveConversion() {
        val nextState = !settings.isLiveConversionEnabled
        preferencesManager.setLiveConversionEnabled(nextState)
        val status = if (nextState) "有効" else "無効"
        composingTextView.text = "ライブ変換: $status"
    }

    private fun onToggleDetailDisplay() {
        showCandidateDetails = !showCandidateDetails
        updateComposingText()
    }

    private fun onDpadUp(isShift: Boolean = false) {
        val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
        val eventTime = SystemClock.uptimeMillis()
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, metaState))
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, metaState))
    }

    private fun onDpadDown(isShift: Boolean = false) {
        val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
        val eventTime = SystemClock.uptimeMillis()
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0, metaState))
        currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0, metaState))
    }

    private fun onCandidateSelected(hiragana: String, cand: String) {
        val pureCand = cand.extractPureCandidate()
        if (settings.isLearningEnabled) {
            engine.recordLearning(hiragana, pureCand)
        }
        lastCommittedSurface = pureCand
        lastCommittedReading = hiragana
        currentInputConnection?.commitText(pureCand, 1)
        clearComposingState()
    }

    private fun String.extractPureCandidate(): String {
        val idx = this.indexOf(" (")
        return if (idx > 0) this.substring(0, idx) else this
    }

    private fun clearComposingState() {
        cancelLiveConversion()
        romajiBuffer.clear()
        currentLiveCandidate = null
        selectedCandidateIndex = -1
        activeSourceFilter = SourceFilter.ALL
        currentCandidates = emptyList()
        isLiveConversionPreviewActive = false
        currentInputConnection?.finishComposingText()
        updateComposingText()
        clearCandidateBar()
    }

    private fun commitCurrentHiragana() {
        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) return

        val hiraganaRaw = engine.romajiToHiraganaFlush(raw)
        val hiragana = if (isKatakanaMode) engine.hiraganaToKatakana(hiraganaRaw) else hiraganaRaw
        val commitTextValue = (currentLiveCandidate ?: hiragana).extractPureCandidate()

        if (settings.isLearningEnabled && commitTextValue != hiragana) {
            engine.recordLearning(hiragana, commitTextValue)
        }

        lastCommittedSurface = commitTextValue
        lastCommittedReading = hiragana
        currentInputConnection?.commitText(commitTextValue, 1)
        clearComposingState()
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        currentEditorInfo = attribute
        requestCursorUpdates()
        val effectiveLayout = getEffectiveKeyboardLayout()
        if (pendingInputViewReload || (currentEffectiveKeyboardLayout != null && currentEffectiveKeyboardLayout != effectiveLayout)) {
            pendingInputViewReload = false
            setInputView(onCreateInputView())
        } else {
            updateKeyboardLayoutVisibility()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        requestCursorUpdates()
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
        dismissFloatingCandidates()
        if (settings.isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
    }



    private var lastF3TimeMs: Long = 0L
    private var lastF4TimeMs: Long = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = KeyEvent.keyCodeToString(keyCode)
        Log.d(TAG, "onKeyDown: keyCode=$keyCode ($keyName), repeatCount=${event?.repeatCount}, unicodeChar=${event?.unicodeChar}, scanCode=${event?.scanCode}, event=$event")

        val currentTime = SystemClock.uptimeMillis()
        if (keyCode == KeyEvent.KEYCODE_F3) {
            lastF3TimeMs = currentTime
        } else if (keyCode == KeyEvent.KEYCODE_F8 && event?.deviceId == -1 && (currentTime - lastF3TimeMs) < 300) {
            Log.d(TAG, "onKeyDown: Suppressing synthetic F8 event generated after F3 (Mode1 Retro Mode Key)")
            return true
        } else if (keyCode == KeyEvent.KEYCODE_F4) {
            lastF4TimeMs = currentTime
        } else if (keyCode == KeyEvent.KEYCODE_F9 && event?.deviceId == -1 && (currentTime - lastF4TimeMs) < 300) {
            Log.d(TAG, "onKeyDown: Suppressing synthetic F9 event generated after F4 (Mode1 Retro Symbol Key)")
            return true
        }

        if (::karukanInputView.isInitialized && karukanInputView.isCandidateExpanded && keyCode == KeyEvent.KEYCODE_BACK) {
            collapseCandidates()
            return true
        }

        val isNikoMode = getEffectiveKeyboardLayout() == "niko"
        val nikoKeyIndex = NikoKeyboardLayout.keyCodeToNikoKeyIndex(keyCode)
        if (!isEnglishMode && isNikoMode && nikoKeyIndex != null && ::keyboardLayoutBuilder.isInitialized) {
            Log.d(TAG, "onKeyDown: Physical NikoTouch key handled: index=$nikoKeyIndex, keyCode=$keyCode ($keyName)")
            triggerKeyFeedback()
            keyboardLayoutBuilder.onNikoKeyClick(nikoKeyIndex)
            return true
        }

        val action = KeyEventMapper.fromKeyEvent(keyCode, event)
        Log.d(TAG, "onKeyDown: mapped action=$action for keyCode=$keyCode ($keyName)")

        if (action != null) {
            if (executeKeyAction(action, isPhysicalKeyboard = true)) {
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyUp: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}), event=$event")
        return super.onKeyUp(keyCode, event)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return settings.isForceShowSoftKeyboardEnabled || super.onEvaluateInputViewShown()
    }

    private fun isHardKeyboardConnected(): Boolean {
        val config = resources.configuration
        val isPresent = config.keyboard == Configuration.KEYBOARD_QWERTY ||
                config.keyboard == Configuration.KEYBOARD_12KEY ||
                config.keyboard != Configuration.KEYBOARD_NOKEYS
        return isPresent && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun is12KeyHardKeyboardConnected(): Boolean {
        val config = resources.configuration
        return config.keyboard == Configuration.KEYBOARD_12KEY &&
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun updateKeyboardLayoutVisibility() {
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateKeyboardLayoutVisibility()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateInputViewShown()
        val effectiveLayout = getEffectiveKeyboardLayout()
        if (currentEffectiveKeyboardLayout != null && currentEffectiveKeyboardLayout != effectiveLayout) {
            currentEffectiveKeyboardLayout = effectiveLayout
            setInputView(onCreateInputView())
        } else {
            updateKeyboardLayoutVisibility()
        }
    }
}
