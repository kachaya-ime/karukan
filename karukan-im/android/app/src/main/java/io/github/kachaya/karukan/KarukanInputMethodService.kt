package io.github.kachaya.karukan

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Karukan IME の Android InputMethodService 実装クラス (QWERTY & ニコタッチ 12キー入力対応)
 */
class KarukanInputMethodService : InputMethodService() {

    // ---------------------------------------------------------------------------------------------
    // Region: Properties & State
    // ---------------------------------------------------------------------------------------------

    private lateinit var engine: KarukanEngine
    private val romajiBuffer = StringBuilder()

    // 直前確定記憶 (確定取り消し・再変換用)
    private var lastCommittedSurface: String = ""
    private var lastCommittedReading: String = ""

    private lateinit var karukanInputView: KarukanInputView
    private lateinit var composingTextView: TextView

    // カーソル位置情報 (物理キーボード接続時フローティング表示用)
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

    // キーボードレイアウト・コンテナ
    private lateinit var mainKeyboardContainer: View
    private lateinit var keyboardLayoutBuilder: KeyboardLayoutBuilder

    // ライブ変換 Coroutine スコープ
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveConversionJob: Job? = null
    private var currentLiveConversionQueryId = 0L
    private var currentLiveCandidate: String? = null

    // システムサービス
    private var audioManager: AudioManager? = null
    private var powerManager: PowerManager? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null

    private var lastF3TimeMs: Long = 0L
    private var lastF4TimeMs: Long = 0L

    companion object {
        private const val TAG = "KarukanInputService"
        private const val LIVE_CONVERSION_DEBOUNCE_MS = 100L
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate() {
        setTheme(R.style.Theme_Karukan)
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        powerManager = getSystemService(POWER_SERVICE) as? PowerManager

        engine = KarukanEngine(this).apply {
            loadProgressListener = KarukanEngine.OnEngineLoadProgressListener { message, _ ->
                if (::composingTextView.isInitialized) {
                    if (isPreparing) {
                        if (romajiBuffer.isEmpty()) {
                            composingTextView.text = message
                        }
                    } else {
                        updateComposingText()
                    }
                }
            }
        }

        preferencesManager = PreferencesManager(this) { updatedSettings ->
            if (::engine.isInitialized) {
                engine.updateEngineConfig(updatedSettings)
            }
            serviceScope.launch {
                pendingInputViewReload = true
                if (isInputViewShown) {
                    setInputView(onCreateInputView())
                    pendingInputViewReload = false
                }
                if (romajiBuffer.isNotEmpty()) {
                    performConversion()
                }
            }
        }
        preferencesManager.startListening()

        if (::engine.isInitialized) {
            engine.updateEngineConfig(settings)
        }

        // Thermal Adaptive AI (Android 10+)
        try {
            thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
                val isOverheating = status >= PowerManager.THERMAL_STATUS_MODERATE
                val targetThreads = if (isOverheating) 1 else settings.nThreads
                if (::engine.isInitialized) {
                    val adaptedSettings = settings.copy(nThreads = targetThreads)
                    engine.updateEngineConfig(adaptedSettings)
                }
            }
            thermalStatusListener?.let { listener ->
                powerManager?.addThermalStatusListener(mainExecutor, listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ThermalStatusListener not supported or failed to register", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thermalStatusListener?.let { listener ->
            try {
                powerManager?.removeThermalStatusListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove ThermalStatusListener", e)
            }
        }
        dismissFloatingCandidates()
        cancelLiveConversion()
        serviceScope.cancel()
        preferencesManager.stopListening()
    }

    override fun onCreateInputView(): View {
        val effectiveLayout = getEffectiveKeyboardLayout()
        currentEffectiveKeyboardLayout = effectiveLayout

        keyboardLayoutBuilder = KeyboardLayoutBuilder(
            context = this,
            isDarkThemeSupplier = { isDarkTheme() },
            keyButtonHeightSupplier = { getKeyButtonHeight() },
            keyboardHeightPxSupplier = { getKeyboardHeightPx() },
            isEnglishModeSupplier = { isEnglishMode },
            inputModeSupplier = { inputMode },
            listener = keyboardEventListener
        )

        karukanInputView = KarukanInputView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            listener = inputViewListener
            setupKeyboardLayout(effectiveLayout)
            setInputMode(inputMode)
        }
        composingTextView = karukanInputView.composingTextView
        mainKeyboardContainer = karukanInputView.mainKeyboardContainer

        return karukanInputView
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

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        currentEditorInfo = attribute
        requestCursorUpdates()
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
        val effectiveLayout = getEffectiveKeyboardLayout()
        if (pendingInputViewReload || currentEffectiveKeyboardLayout != effectiveLayout) {
            pendingInputViewReload = false
            setInputView(onCreateInputView())
        } else if (::karukanInputView.isInitialized) {
            karukanInputView.setupKeyboardLayout(effectiveLayout)
            karukanInputView.setInputMode(inputMode)
            updateKeyboardLayoutVisibility()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        dismissFloatingCandidates()
        if (settings.isAutoCommitEnabled && romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
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

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Region: UI & Theme Helpers
    // ---------------------------------------------------------------------------------------------

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
            if (mainKeyboardContainer.height > 0) return mainKeyboardContainer.height
            if (mainKeyboardContainer.measuredHeight > 0) return mainKeyboardContainer.measuredHeight
        }
        val rows = KeyboardLayoutRegistry.getProvider(getEffectiveKeyboardLayout()).rowCount
        return getKeyButtonHeight() * rows
    }

    private var currentEffectiveKeyboardLayout: String? = null

    private fun getEffectiveKeyboardLayout(): String {
        if (isHardKeyboardConnected()) {
            return when (settings.physicalKeyboardLayout) {
                "titan", "clicks" -> "builtin"
                "niko" -> "niko"
                else -> settings.keyboardLayout
            }
        }
        return settings.keyboardLayout
    }

    private fun getSpaceString(): String = if (settings.spaceStyle == "full") "　" else " "

    private fun getSourceFilterLabel(filter: SourceFilter): String = when (filter) {
        SourceFilter.ALL -> "全体"
        SourceFilter.LEARNING -> "📝学習"
        SourceFilter.DICTIONARY -> "📚辞書"
        SourceFilter.AI -> "🤖AI"
        SourceFilter.SYMBOL -> "🔄記号"
    }

    fun openSettingsActivity() {
        val intent = android.content.Intent(this, SettingsActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun isHardKeyboardConnected(): Boolean {
        val config = resources.configuration
        val isPresent = config.keyboard == Configuration.KEYBOARD_QWERTY ||
                config.keyboard == Configuration.KEYBOARD_12KEY ||
                config.keyboard != Configuration.KEYBOARD_NOKEYS
        return isPresent && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun updateKeyboardLayoutVisibility() {
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateKeyboardLayoutVisibility()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Cursor Position Tracking
    // ---------------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------------
    // Region: View Listener Declarations
    // ---------------------------------------------------------------------------------------------

    private val inputViewListener = object : KarukanInputView.Listener {
        override fun isDarkTheme(): Boolean = this@KarukanInputMethodService.isDarkTheme()
        override fun isHardKeyboardConnected(): Boolean = this@KarukanInputMethodService.isHardKeyboardConnected()
        override fun getKeyboardHeightPx(): Int = this@KarukanInputMethodService.getKeyboardHeightPx()
        override fun getEngine(): KarukanEngine = engine
        override fun getSettings(): KarukanSettings = settings
        override fun openSettingsActivity() = this@KarukanInputMethodService.openSettingsActivity()
        override fun onCandidateSelected(hiragana: String, candidate: String, index: Int) {
            this@KarukanInputMethodService.selectedCandidateIndex = index
            this@KarukanInputMethodService.onCandidateSelected(hiragana, candidate)
        }

        override fun onKeyFeedback() = keyboardEventListener.onKeyFeedback()
        override fun onKeyPress(key: String, isShift: Boolean) = keyboardEventListener.onKeyPress(key, isShift)
        override fun onNikoTouchInput(char: String) = keyboardEventListener.onNikoTouchInput(char)
        override fun onNikoTouchToggle() = keyboardEventListener.onNikoTouchToggle()
        override fun onBackspace() = keyboardEventListener.onBackspace()
        override fun onDelete() = keyboardEventListener.onDelete()
        override fun onCtrlBackspace() = keyboardEventListener.onCtrlBackspace()
        override fun onSpaceOrConvert() = keyboardEventListener.onSpaceOrConvert()
        override fun onCommit() = keyboardEventListener.onCommit()
        override fun onToggleEmojiPalette() = keyboardEventListener.onToggleEmojiPalette()
        override fun onToggleSymbolPalette() = keyboardEventListener.onToggleSymbolPalette()
        override fun onToggleLanguageMode() = keyboardEventListener.onToggleLanguageMode()
        override fun onNikoToggleLanguageMode() = keyboardEventListener.onNikoToggleLanguageMode()
        override fun onDpadLeft() = keyboardEventListener.onDpadLeft()
        override fun onDpadRight() = keyboardEventListener.onDpadRight()
        override fun onDpadUp() = keyboardEventListener.onDpadUp()
        override fun onDpadDown() = keyboardEventListener.onDpadDown()
        override fun onCtrlShortcut(key: String) = keyboardEventListener.onCtrlShortcut(key)
        override fun onEsc() = keyboardEventListener.onEsc()
        override fun onTab() = keyboardEventListener.onTab()
        override fun onKatakana() = keyboardEventListener.onKatakana()
        override fun onFixChunk() = keyboardEventListener.onFixChunk()
    }

    private val keyboardEventListener = object : KeyboardLayoutBuilder.KeyboardEventListener {
        override fun onKeyFeedback() = triggerKeyFeedback()
        override fun onKeyPress(key: String, isShift: Boolean) {
            executeKeyAction(KeyAction.InputChar(key, isShift))
        }

        override fun onNikoTouchInput(char: String) {
            executeKeyAction(KeyAction.NikoTouchInput(char))
        }

        override fun onNikoTouchToggle() {
            executeKeyAction(KeyAction.NikoTouchToggle)
        }

        override fun onBackspace() {
            executeKeyAction(KeyAction.Backspace)
        }

        override fun onDelete() {
            executeKeyAction(KeyAction.DeleteForward)
        }

        override fun onCtrlBackspace() {
            executeKeyAction(KeyAction.DeleteSelectedCandidate)
        }

        override fun onSpaceOrConvert() {
            executeKeyAction(KeyAction.SpaceOrConvert)
        }

        override fun onCommit() {
            executeKeyAction(KeyAction.Commit())
        }

        override fun onToggleEmojiPalette() {
            executeKeyAction(KeyAction.ToggleEmojiPalette)
        }

        override fun onToggleSymbolPalette() {
            executeKeyAction(KeyAction.ToggleSymbolPalette)
        }

        override fun onToggleLanguageMode() {
            executeKeyAction(KeyAction.ToggleLanguageMode)
        }

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
                karukanInputView.setInputMode(inputMode)
            }
        }

        override fun onFixChunk() {
            executeKeyAction(KeyAction.FixChunk)
        }

        override fun onDpadLeft() {
            executeKeyAction(KeyAction.MoveLeft())
        }

        override fun onDpadRight() {
            executeKeyAction(KeyAction.MoveRight())
        }

        override fun onDpadUp() {
            executeKeyAction(KeyAction.MoveUp())
        }

        override fun onDpadDown() {
            executeKeyAction(KeyAction.MoveDown())
        }

        override fun onCtrlShortcut(key: String) {
            executeKeyAction(KeyAction.CtrlShortcut(key))
        }

        override fun onEsc() {
            executeKeyAction(KeyAction.Escape)
        }

        override fun onTab() {
            executeKeyAction(KeyAction.Tab())
        }

        override fun onKatakana() {
            executeKeyAction(KeyAction.CommitKatakana)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Key Action Dispatcher
    // ---------------------------------------------------------------------------------------------

    fun executeKeyAction(action: KeyAction, isPhysicalKeyboard: Boolean = false): Boolean {
        when (action) {
            is KeyAction.InputChar -> onKeyPress(action.text, action.isShift)
            is KeyAction.NikoTouchInput -> onNikoTouchInput(action.char)
            KeyAction.NikoTouchToggle -> onNikoTouchToggle()
            KeyAction.Backspace -> onBackspace()
            KeyAction.DeleteForward -> onDeleteForward()
            KeyAction.DeleteSelectedCandidate -> onDeleteSelectedCandidate()
            KeyAction.SpaceOrConvert -> onSpaceKey()
            KeyAction.FullWidthSpace -> {
                handleSpecialShortcut("space_shift") { onFullWidthSpace() }
            }
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
                convertHiragana()
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

            KeyAction.ToggleLanguageMode -> {
                handleSpecialShortcut("space_ctrl") { onToggleLanguageMode() }
            }
            KeyAction.Henkan -> onHenkanKey()
            KeyAction.KanaMode -> onKanaKey()
            KeyAction.EisuMode -> onEisuKey()
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
            KeyAction.Undo -> undoEditingArea()
            KeyAction.Cut -> cutEditingArea()
            KeyAction.Copy -> copyEditingArea()
            KeyAction.Paste -> pasteEditingArea()
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

    // ---------------------------------------------------------------------------------------------
    // Region: Physical Keyboard Input Handlers
    // ---------------------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentTime = SystemClock.uptimeMillis()
        if (keyCode == KeyEvent.KEYCODE_F3) {
            lastF3TimeMs = currentTime
        } else if (keyCode == KeyEvent.KEYCODE_F8 && event?.deviceId == -1 && (currentTime - lastF3TimeMs) < 300) {
            return true
        } else if (keyCode == KeyEvent.KEYCODE_F4) {
            lastF4TimeMs = currentTime
        } else if (keyCode == KeyEvent.KEYCODE_F9 && event?.deviceId == -1 && (currentTime - lastF4TimeMs) < 300) {
            return true
        }

        if (::karukanInputView.isInitialized && karukanInputView.isCandidateExpanded && keyCode == KeyEvent.KEYCODE_BACK) {
            collapseCandidates()
            return true
        }

        val isNikoMode = getEffectiveKeyboardLayout() == "niko"
        val nikoKeyIndex = NikoKeyboardLayout.keyCodeToNikoKeyIndex(keyCode)
        if (!isEnglishMode && isNikoMode && nikoKeyIndex != null && ::keyboardLayoutBuilder.isInitialized) {
            triggerKeyFeedback()
            NikoKeyboardLayout.onNikoKeyClick(nikoKeyIndex)
            return true
        }

        val action = KeyEventMapper.fromKeyEvent(keyCode, event, settings.physicalKeyboardLayout)

        // 1. Android 固有の編集アクション (Ctrl+A/C/V/X/Z, 全選択, コピー, 画面UI切替等) を最優先でハンドリング
        if (action != null && isAndroidSpecificAction(action)) {
            if (executeKeyAction(action, isPhysicalKeyboard = true)) {
                return true
            }
        }

        // 2. 文字入力・カーソル移動・変換・削除等は Native (Core Engine) の InputBuffer を優先試行
        val nativePair = KeyEventMapper.toNativeKeysymAndModifiers(keyCode, event, settings.physicalKeyboardLayout)
        if (nativePair != null) {
            val (keysym, modifiers) = nativePair
            val res = engine.processKey(keysym, modifiers, isRelease = false)
            if (applyProcessKeyResult(res)) {
                return true
            }
        }

        // 3. Fallback: その他の標準アクション
        if (action != null) {
            if (executeKeyAction(action, isPhysicalKeyboard = true)) {
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    private fun isAndroidSpecificAction(action: KeyAction): Boolean {
        return when (action) {
            KeyAction.SelectAll,
            KeyAction.Copy,
            KeyAction.Paste,
            KeyAction.Cut,
            KeyAction.Undo,
            KeyAction.ToggleEmojiPalette,
            KeyAction.ToggleSymbolPalette,
            KeyAction.ToggleLanguageMode,
            KeyAction.ToggleLiveConversion,
            KeyAction.ToggleDetailDisplay -> true
            is KeyAction.CtrlShortcut -> {
                action.key in setOf("a", "c", "v", "x", "z", "l", "e")
            }
            else -> false
        }
    }

    private fun applyProcessKeyResult(result: ProcessKeyResult): Boolean {
        if (!result.consumed) return false

        result.commitText?.let { commitStr ->
            if (commitStr.isNotEmpty()) {
                currentInputConnection?.commitText(commitStr, 1)
                lastCommittedSurface = commitStr
            }
        }

        if (result.hasPreedit && result.preeditText != null) {
            val preedit = result.preeditText
            if (preedit.isNotEmpty()) {
                val caretPos = if (result.caret >= preedit.length) 1 else result.caret - preedit.length
                currentInputConnection?.setComposingText(preedit, caretPos)
                if (::composingTextView.isInitialized) {
                    composingTextView.text = preedit
                }
            } else {
                currentInputConnection?.finishComposingText()
            }
        } else if (result.commitText != null && !result.hasPreedit) {
            clearComposingState()
        }

        if (result.hasCandidates && result.candidates != null) {
            currentCandidates = result.candidates
            renderCandidates("", currentCandidates)
        } else if (result.hideCandidates) {
            clearCandidateBar()
        }

        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Keyboard Command Action Handlers
    // ---------------------------------------------------------------------------------------------

    private fun onKeyPress(key: String, isShift: Boolean = false) {
        if (isEnglishMode) {
            currentInputConnection?.commitText(key, 1)
            return
        }

        if (isShift && key.length == 1 && key[0] in 'A'..'Z') {
            if (romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            inputMode = InputMode.ALPHANUMERIC
            currentInputConnection?.commitText(key.uppercase(Locale.getDefault()), 1)
            if (::karukanInputView.isInitialized) {
                karukanInputView.setInputMode(inputMode)
            }
            return
        }

        if (selectedCandidateIndex >= 0) {
            selectedCandidateIndex = -1
        }

        romajiBuffer.append(key)
        updateComposingText()
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

    private fun onHenkanKey() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onSpaceKey()
        } else {
            if (!reconvert()) {
                currentInputConnection?.commitText(getSpaceString(), 1)
            }
        }
    }

    private fun onKanaKey() {
        if (romajiBuffer.isNotEmpty()) {
            convertHiragana()
        } else {
            inputMode = InputMode.HIRAGANA
            isKatakanaMode = false
            clearComposingState()
            if (::karukanInputView.isInitialized) {
                karukanInputView.setInputMode(inputMode)
            }
        }
    }

    private fun onEisuKey() {
        if (romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
        inputMode = InputMode.ALPHANUMERIC
        clearComposingState()
        if (::karukanInputView.isInitialized) {
            karukanInputView.setInputMode(inputMode)
        }
    }

    private fun onFullWidthSpace() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
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

    private fun onToggleLanguageMode() {
        if (romajiBuffer.isNotEmpty()) {
            commitCurrentHiragana()
        }
        val isEng = inputMode != InputMode.HIRAGANA
        inputMode = if (isEng) InputMode.HIRAGANA else InputMode.ALPHANUMERIC
        clearComposingState()
        if (::karukanInputView.isInitialized) {
            karukanInputView.setInputMode(inputMode)
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

    // ---------------------------------------------------------------------------------------------
    // Region: Composing Text & Conversion Pipeline
    // ---------------------------------------------------------------------------------------------

    private fun updateComposingText() {
        requestCursorUpdates()
        val hasInput = romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateNikoInputState(hasInput)
        }
        if (isEnglishMode) {
            if (::engine.isInitialized && engine.isPreparing && romajiBuffer.isEmpty()) {
                composingTextView.text = engine.currentLoadStatus
            } else {
                composingTextView.text = "英数モード"
            }
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        val raw = romajiBuffer.toString()
        if (raw.isEmpty()) {
            if (::engine.isInitialized && engine.isPreparing) {
                composingTextView.text = engine.currentLoadStatus
            } else {
                val katakanaTag = if (isKatakanaMode) " [カタカナ]" else ""
                composingTextView.text = "かな: (なし)$katakanaTag"
            }
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
            composingTextView.text = "かな: $hiragana$katakanaTag"
            currentInputConnection?.setComposingText(hiragana, 1)
            if (::karukanInputView.isInitialized) {
                karukanInputView.updateFloatingOverlay(selectedCandidateIndex)
            }
            return
        }

        // ライブ変換
        isLiveConversionPreviewActive = true
        composingTextView.text = "かな: $hiragana$katakanaTag"
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
                val pureTopCandidate = topCandidate.extractPureCandidate()
                currentLiveCandidate = pureTopCandidate
                val detailStr = if (showCandidateDetails) " (推論: Live, 候補数: ${candidates.size})" else ""
                val displayTopCandidate = if (settings.isShowCandidateAnnotationsEnabled) topCandidate else pureTopCandidate
                composingTextView.text = "かな: $flushHiragana → $displayTopCandidate$katakanaTag$detailStr"
                currentInputConnection?.setComposingText(pureTopCandidate, 1)
                renderCandidates(flushHiragana, candidates)
            }
        }
    }

    private fun cancelLiveConversion() {
        liveConversionJob?.cancel()
        liveConversionJob = null
        if (currentLiveConversionQueryId > 0) {
            engine.cancelQuery(currentLiveConversionQueryId)
        }
    }

    private fun fetchEmojiCandidates(query: String): List<String> =
        CandidateFilterManager.fetchEmojiCandidates(engine, query)

    private fun fetchCandidates(hiragana: String, queryId: Long = 0): List<String> {
        if (hiragana.isEmpty()) return emptyList()

        var leftContext = ""
        if (settings.useContext) {
            val rawContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars + hiragana.length + 20, 0)?.toString() ?: ""
            val rawBuffer = romajiBuffer.toString()
            val currentLineContext = rawContext.substringAfterLast('\n').substringAfterLast('\r')
            var cleanedContext = currentLineContext

            var changed = true
            while (changed && cleanedContext.isNotEmpty()) {
                changed = false
                val liveCand = currentLiveCandidate?.extractPureCandidate() ?: ""
                if (liveCand.isNotEmpty() && cleanedContext.endsWith(liveCand)) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - liveCand.length)
                    changed = true
                } else if (hiragana.isNotEmpty() && cleanedContext.endsWith(hiragana)) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - hiragana.length)
                    changed = true
                } else if (rawBuffer.isNotEmpty() && cleanedContext.endsWith(rawBuffer)) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - rawBuffer.length)
                    changed = true
                }
            }

            for (len in hiragana.length downTo 1) {
                if (cleanedContext.length >= len &&
                    cleanedContext.regionMatches(cleanedContext.length - len, hiragana, 0, len)
                ) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - len)
                    break
                }
            }
            for (len in rawBuffer.length downTo 1) {
                if (cleanedContext.length >= len &&
                    cleanedContext.regionMatches(cleanedContext.length - len, rawBuffer, 0, len)
                ) {
                    cleanedContext = cleanedContext.substring(0, cleanedContext.length - len)
                    break
                }
            }

            cleanedContext = cleanedContext.replace(Regex("""\s*\([👤🤖📝🔄][^)]*\)"""), "")

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

        val rawCandidates = engine.convert(hiragana, 50, fullContext, queryId)
        return if (settings.isShowCandidateAnnotationsEnabled) {
            rawCandidates
        } else {
            rawCandidates.map { it.extractPureCandidate() }.distinct()
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
        if (currentCandidates.isNotEmpty()) {
            selectedCandidateIndex = 0
            updateSelectionPreview()
        }
    }

    private fun performConversionNoLearning() {
        if (isEnglishMode || romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val hiragana = engine.romajiToHiraganaFlush(raw)
        val candidates = fetchCandidates(hiragana)
        selectedCandidateIndex = 0
        renderCandidates(hiragana, candidates)
        updateSelectionPreview()
    }

    private fun convertPreeditTo(convertedText: String) {
        if (romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val hiragana = engine.romajiToHiraganaFlush(raw)

        currentCandidates = listOf(convertedText)
        selectedCandidateIndex = 0
        composingTextView.text = "かな: $hiragana → $convertedText [1/1]"
        currentInputConnection?.setComposingText(convertedText, 1)
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateCandidates(hiragana, currentCandidates, selectedCandidateIndex)
        }
    }

    private fun convertHiragana() {
        if (romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val hiragana = engine.romajiToHiraganaFlush(raw)
        convertPreeditTo(hiragana)
    }

    private fun commitKatakanaOrToggleMode() {
        if (romajiBuffer.isNotEmpty()) {
            val raw = romajiBuffer.toString()
            val hiragana = engine.romajiToHiraganaFlush(raw)
            val katakana = engine.hiraganaToKatakana(hiragana)
            convertPreeditTo(katakana)
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

    private fun commitHankakuKatakana() {
        if (romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val hiragana = engine.romajiToHiraganaFlush(raw)
        val katakana = engine.hiraganaToKatakana(hiragana)
        val hankakuKatakana = toHankakuKatakana(katakana)
        convertPreeditTo(hankakuKatakana)
    }

    private fun commitZenkakuAlphanumeric() {
        if (romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val current = currentCandidates.getOrNull(selectedCandidateIndex)
        val lower = toZenkakuAlphanumeric(raw.lowercase(Locale.getDefault()))
        val upper = toZenkakuAlphanumeric(raw.uppercase(Locale.getDefault()))
        val cap = toZenkakuAlphanumeric(raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })

        val target = when (current) {
            lower -> upper
            upper -> cap
            else -> lower
        }
        convertPreeditTo(target)
    }

    private fun commitHankakuAlphanumeric() {
        if (romajiBuffer.isEmpty()) return
        val raw = romajiBuffer.toString()
        val current = currentCandidates.getOrNull(selectedCandidateIndex)
        val lower = raw.lowercase(Locale.getDefault())
        val upper = raw.uppercase(Locale.getDefault())
        val cap = raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        val target = when (current) {
            lower -> upper
            upper -> cap
            else -> lower
        }
        convertPreeditTo(target)
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

    // ---------------------------------------------------------------------------------------------
    // Region: Candidate Rendering & Filtering
    // ---------------------------------------------------------------------------------------------

    private fun filterCandidatesBySource(rawCandidates: List<String>): List<String> =
        CandidateFilterManager.filterCandidatesBySource(rawCandidates, activeSourceFilter, engine)

    private fun renderCandidates(hiragana: String, rawCandidates: List<String>) {
        val isHardKb = isHardKeyboardConnected()
        val isSuggestEnabled = settings.isShowPhysicalKeyboardSuggestCandidatesEnabled

        if (isHardKb && !isSuggestEnabled && selectedCandidateIndex < 0) {
            currentCandidates = emptyList()
            if (::karukanInputView.isInitialized) {
                karukanInputView.clearCandidateBar(isEnglishMode)
                karukanInputView.dismissFloatingCandidates()
            }
            return
        }

        val candidatesToProcess = if (settings.isShowCandidateAnnotationsEnabled) {
            rawCandidates
        } else {
            rawCandidates.map { it.extractPureCandidate() }.distinct()
        }
        val filtered = filterCandidatesBySource(candidatesToProcess)
        val allCandidates = mutableListOf<String>()
        if (filtered.isNotEmpty()) {
            allCandidates.addAll(filtered)
        }
        if (!allCandidates.contains(hiragana) && activeSourceFilter == SourceFilter.ALL) {
            allCandidates.add(hiragana)
        }
        currentCandidates = allCandidates

        if (::karukanInputView.isInitialized) {
            karukanInputView.updateCandidates(hiragana, allCandidates, selectedCandidateIndex)
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
        if (currentCandidates.isEmpty()) {
            if (romajiBuffer.isNotEmpty()) {
                performConversion()
                if (currentCandidates.isNotEmpty()) {
                    selectedCandidateIndex = currentCandidates.size - 1
                    updateSelectionPreview()
                }
            }
            return
        }
        selectedCandidateIndex = if (selectedCandidateIndex <= 0) currentCandidates.size - 1 else selectedCandidateIndex - 1
        updateSelectionPreview()
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

    private fun filterByAiSource() {
        activeSourceFilter = SourceFilter.AI
        performConversion()
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

        if (settings.isMoveCursorInsideBracketEnabled && isPairedBracket(pureCand)) {
            val eventTime = SystemClock.uptimeMillis()
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0))
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0, 0))
        }
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

    // ---------------------------------------------------------------------------------------------
    // Region: OS Clipboard & Editing Shortcuts
    // ---------------------------------------------------------------------------------------------

    private fun executeActionById(actionId: String): Boolean {
        val isComposing = romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0
        when (actionId) {
            "none" -> return false
            "select_all" -> selectAllEditingArea()
            "copy" -> copyEditingArea()
            "paste" -> pasteEditingArea()
            "cut" -> cutEditingArea()
            "undo" -> undoEditingArea()
            "katakana" -> commitKatakanaOrToggleMode()
            "hiragana" -> convertHiragana()
            "hankaku_katakana" -> commitHankakuKatakana()
            "zenkaku_alpha" -> commitZenkakuAlphanumeric()
            "hankaku_alpha" -> commitHankakuAlphanumeric()
            "ai_candidates" -> filterByAiSource()
            "fix_chunk" -> {
                if (!isComposing) return false
                fixChunkPreedit()
            }
            "symbol_view" -> {
                activeSourceFilter = SourceFilter.SYMBOL
                performConversion()
            }
            "learning_view" -> {
                activeSourceFilter = SourceFilter.LEARNING
                performConversion()
            }
            "reconvert" -> {
                if (isComposing) return false
                reconvert()
            }
            "toggle_live_conversion" -> onToggleLiveConversion()
            "toggle_emoji_palette" -> {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleEmojiPalette()
                }
            }
            "toggle_detail" -> onToggleDetailDisplay()
            "toggle_candidate_expand" -> {
                if (::karukanInputView.isInitialized) {
                    karukanInputView.toggleCandidateExpand()
                }
            }
            "toggle_language_mode" -> onToggleLanguageMode()
            "cursor_left" -> onDpadLeft()
            "cursor_right" -> onDpadRight()
            "commit" -> onCommitKey()
            "escape" -> onEscape()
            "backspace" -> onBackspace()
            "delete_forward" -> onDeleteForward()
            "next_candidate" -> {
                if (isComposing) {
                    onNextCandidate()
                } else {
                    onDpadDown()
                }
            }
            "prev_candidate" -> {
                if (isComposing) {
                    onPreviousCandidate()
                } else {
                    onDpadUp()
                }
            }
            "full_width_space" -> onFullWidthSpace()
            else -> return false
        }
        return true
    }

    private fun handleCtrlShortcut(key: String): Boolean {
        val lowerKey = key.lowercase(Locale.getDefault())
        val shortcutItems = KeyShortcutItem.fromJsonString(settings.keyShortcutsJson)
        val item = shortcutItems.firstOrNull { it.shortcutKey == lowerKey } ?: return false
        return executeActionById(item.actionId)
    }

    private fun handleSpecialShortcut(shortcutKey: String, fallbackAction: () -> Unit) {
        val shortcutItems = KeyShortcutItem.fromJsonString(settings.keyShortcutsJson)
        val item = shortcutItems.firstOrNull { it.shortcutKey == shortcutKey }
        if (item != null && item.actionId != "none") {
            executeActionById(item.actionId)
        } else {
            fallbackAction()
        }
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

    private fun selectAllEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun undoEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
    }

    private fun cutEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.cut)
    }

    private fun copyEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.copy)
    }

    private fun pasteEditingArea() {
        if (romajiBuffer.isNotEmpty() || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: String & Character Utilities
    // ---------------------------------------------------------------------------------------------

    private fun toHankakuKatakana(str: String): String = TextTransformUtils.toHankakuKatakana(str)

    private fun toZenkakuAlphanumeric(str: String): String = TextTransformUtils.toZenkakuAlphanumeric(str)

    private fun isPairedBracket(text: String): Boolean = TextTransformUtils.isPairedBracket(text)

    private fun String.extractPureCandidate(): String {
        val lastOpen = this.lastIndexOf(" (")
        return if (lastOpen > 0 && this.endsWith(")")) this.substring(0, lastOpen) else this
    }
}
