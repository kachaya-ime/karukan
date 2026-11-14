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
    private var currentPreedit: String = ""
    private var romajiBuffer: String = ""
    private val hasActiveInput: Boolean
        get() = currentPreedit.isNotEmpty()
    private val currentActiveQuery: String
        get() = currentPreedit

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

    companion object {
        private const val TAG = "KarukanInputService"
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
                        if (!hasActiveInput) {
                            composingTextView.text = message
                        }
                    } else {
                        updateComposingText()
                    }
                }
            }
        }

        preferencesManager = PreferencesManager(this) { updatedSettings ->
            invalidateThemeCache()
            invalidateHeightCache()
            invalidateHardKeyboardCache()
            if (::engine.isInitialized) {
                engine.updateEngineConfig(updatedSettings)
            }
            serviceScope.launch {
                pendingInputViewReload = true
                if (isInputViewShown) {
                    setInputView(onCreateInputView())
                    pendingInputViewReload = false
                }
                if (hasActiveInput) {
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

    override fun setInputView(view: View?) {
        super.setInputView(view)
        view?.post {
            view.requestLayout()
        }
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode()) {
            val view = if (::karukanInputView.isInitialized) karukanInputView else null
            val height = if (view != null && view.height > 0) {
                view.height
            } else if (view != null && view.measuredHeight > 0) {
                view.measuredHeight
            } else {
                getKeyboardHeightPx() + getKeyButtonHeight()
            }
            val visibleTop = window?.window?.decorView?.let { it.height - height } ?: 0
            outInsets.contentTopInsets = visibleTop
            outInsets.visibleTopInsets = visibleTop
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute

        if (!restarting) {
            requestCursorUpdates()
            if (settings.isAutoCommitEnabled && hasActiveInput) {
                commitCurrentHiragana()
            }
            currentPreedit = ""
            evaluateEditorInfo(attribute)
            if (::composingTextView.isInitialized) {
                updateComposingText()
                clearCandidateBar()
            }
        } else {
            if (hasActiveInput) {
                currentInputConnection?.setComposingText(currentPreedit, 1)
            }
        }
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        currentEditorInfo = attribute
        if (!restarting && ::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
        val effectiveLayout = getEffectiveKeyboardLayout()
        if (pendingInputViewReload || currentEffectiveKeyboardLayout != effectiveLayout) {
            pendingInputViewReload = false
            currentEffectiveKeyboardLayout = effectiveLayout
            setInputView(onCreateInputView())
        } else if (::karukanInputView.isInitialized) {
            karukanInputView.setInputMode(inputMode)
            updateKeyboardLayoutVisibility()
        }
    }

    private fun evaluateEditorInfo(info: EditorInfo?) {
        if (info == null) return
        val inputType = info.inputType
        val clazz = inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION

        when (clazz) {
            EditorInfo.TYPE_CLASS_NUMBER, EditorInfo.TYPE_CLASS_PHONE, EditorInfo.TYPE_CLASS_DATETIME -> {
                inputMode = InputMode.NUMERIC
            }
            EditorInfo.TYPE_CLASS_TEXT -> {
                when (variation) {
                    EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
                    EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    EditorInfo.TYPE_TEXT_VARIATION_URI -> {
                        inputMode = InputMode.ALPHANUMERIC
                    }
                    else -> {
                        inputMode = InputMode.HIRAGANA
                    }
                }
            }
            else -> {
                inputMode = InputMode.HIRAGANA
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dismissFloatingCandidates()
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        dismissFloatingCandidates()
        if (settings.isAutoCommitEnabled && hasActiveInput) {
            commitCurrentHiragana()
        }
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        dismissFloatingCandidates()
        if (::karukanInputView.isInitialized) {
            karukanInputView.resetKeyboardState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateHardKeyboardCache()
        invalidateThemeCache()
        invalidateHeightCache()
        updateInputViewShown()
        currentEffectiveKeyboardLayout = getEffectiveKeyboardLayout()
        setInputView(onCreateInputView())
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Region: UI & Theme Helpers
    // ---------------------------------------------------------------------------------------------

    private var cachedIsDarkTheme: Boolean? = null
    private var cachedKeyButtonHeight: Int? = null

    private fun invalidateThemeCache() {
        cachedIsDarkTheme = null
    }

    private fun invalidateHeightCache() {
        cachedKeyButtonHeight = null
    }

    private fun isDarkTheme(): Boolean {
        cachedIsDarkTheme?.let { return it }
        val dark = when (settings.keyboardTheme) {
            "light" -> false
            "system" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        cachedIsDarkTheme = dark
        return dark
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
        cachedKeyButtonHeight?.let { return it }
        val density = resources.displayMetrics.density
        val height = when (settings.keyboardHeight) {
            "small" -> (42 * density).toInt()
            "large" -> (62 * density).toInt()
            else -> (52 * density).toInt()
        }
        cachedKeyButtonHeight = height
        return height
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

    private var cachedIsHardKeyboardConnected: Boolean? = null
    private var cachedIsIntegratedHardKeyboardConnected: Boolean? = null

    private fun invalidateHardKeyboardCache() {
        cachedIsHardKeyboardConnected = null
        cachedIsIntegratedHardKeyboardConnected = null
    }

    private fun isHardKeyboardConnected(): Boolean {
        cachedIsHardKeyboardConnected?.let { return it }
        val result = checkIsHardKeyboardConnected()
        cachedIsHardKeyboardConnected = result
        return result
    }

    private fun checkIsHardKeyboardConnected(): Boolean {
        // 1. Check configuration (built-in hard keyboard)
        val config = resources.configuration
        val configHasHardKeyboard = (config.keyboard == Configuration.KEYBOARD_QWERTY ||
                config.keyboard == Configuration.KEYBOARD_12KEY ||
                config.keyboard != Configuration.KEYBOARD_NOKEYS) &&
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

        if (configHasHardKeyboard) return true

        // 2. Check connected input devices (Bluetooth / USB external keyboards like JP109)
        val deviceIds = android.view.InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = android.view.InputDevice.getDevice(id)
            if (device != null && !device.isVirtual()) {
                val sources = device.sources
                if ((sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD) {
                    if (device.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isIntegratedHardKeyboardConnected(): Boolean {
        cachedIsIntegratedHardKeyboardConnected?.let { return it }
        val result = checkIsIntegratedHardKeyboardConnected()
        cachedIsIntegratedHardKeyboardConnected = result
        return result
    }

    private fun checkIsIntegratedHardKeyboardConnected(): Boolean {
        val deviceIds = android.view.InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = android.view.InputDevice.getDevice(id)
            if (device != null && !device.isVirtual()) {
                val sources = device.sources
                if ((sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD) {
                    if (device.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                        if (!device.isExternal()) {
                            return true
                        }
                    }
                }
            }
        }
        return false
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
        override fun isIntegratedHardKeyboardConnected(): Boolean = this@KarukanInputMethodService.isIntegratedHardKeyboardConnected()
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
        override fun onKanaInput(char: String) = keyboardEventListener.onKanaInput(char)
        override fun onNikoTouchInput(char: String) = keyboardEventListener.onKanaInput(char)
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

        override fun onKanaInput(char: String) {
            executeKeyAction(KeyAction.KanaInput(char))
        }

        override fun onNikoTouchInput(char: String) {
            executeKeyAction(KeyAction.KanaInput(char))
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
            if (hasActiveInput) {
                commitCurrentHiragana()
            }
            inputMode = InputMode.SYMBOL
            clearComposingState()
            if (::karukanInputView.isInitialized) {
                karukanInputView.setInputMode(inputMode)
            }
        }

        override fun onToggleLanguageMode() {
            executeKeyAction(KeyAction.ToggleLanguageMode)
        }

        override fun onNikoToggleLanguageMode() {
            if (hasActiveInput) {
                commitCurrentHiragana()
            }
            inputMode = when (inputMode) {
                InputMode.NUMERIC, InputMode.SYMBOL -> InputMode.HIRAGANA
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
            is KeyAction.KanaInput -> onKanaInput(action.char)
            is KeyAction.NikoTouchInput -> onKanaInput(action.char)
            KeyAction.NikoTouchToggle -> onNikoTouchToggle()
            KeyAction.Backspace -> onBackspace()
            KeyAction.DeleteForward -> onDeleteForward()
            KeyAction.DeleteSelectedCandidate -> onDeleteSelectedCandidate()
            KeyAction.SpaceOrConvert -> onSpaceKey()
            KeyAction.FullWidthSpace -> {
                handleSpecialShortcut("space_shift") { onFullWidthSpace() }
            }

            is KeyAction.Commit -> {
                if (isPhysicalKeyboard && !hasActiveInput && selectedCandidateIndex < 0) {
                    return false
                }
                onCommitKey()
            }

            KeyAction.Escape -> onEscape()
            is KeyAction.Tab -> onTabKey(action.isShift)
            KeyAction.CommitHiragana -> {
                if (!hasActiveInput) return false
                convertHiragana()
            }

            KeyAction.CommitKatakana -> {
                if (isPhysicalKeyboard && !hasActiveInput) return false
                commitKatakanaOrToggleMode()
            }

            KeyAction.CommitHankakuKatakana -> {
                if (!hasActiveInput) return false
                commitHankakuKatakana()
            }

            KeyAction.CommitZenkakuAlphanumeric -> {
                if (!hasActiveInput) return false
                commitZenkakuAlphanumeric()
            }

            KeyAction.CommitHankakuAlphanumeric -> {
                if (!hasActiveInput) return false
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
                if (hasActiveInput) {
                    commitCurrentHiragana()
                }
                inputMode = InputMode.SYMBOL
                clearComposingState()
                if (::karukanInputView.isInitialized) {
                    karukanInputView.setInputMode(inputMode)
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
                    val hiragana = currentActiveQuery
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

        if (::karukanInputView.isInitialized && karukanInputView.isCandidateExpanded && keyCode == KeyEvent.KEYCODE_BACK) {
            collapseCandidates()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event?.isCtrlPressed != true) {
                if (event?.isShiftPressed == true) {
                    onFullWidthSpace()
                } else {
                    onSpaceKey()
                }
                return true
            }
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (selectedCandidateIndex >= 0 && selectedCandidateIndex in currentCandidates.indices) {
                onCommitKey()
                return true
            }
        }

        val isNikoMode = (currentEffectiveKeyboardLayout ?: getEffectiveKeyboardLayout()) == "niko"
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


    private fun triggerLiveConversion(preedit: String) {
        cancelLiveConversion()
        val queryId = ++currentLiveConversionQueryId

        // Fetch leftContext safely on the main thread before switching to Dispatchers.Default
        val leftContext = if (settings.useContext) {
            val rawContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars + preedit.length + 10, 0)?.toString() ?: ""
            val currentLine = rawContext.substringAfterLast('\n').substringAfterLast('\r')
            if (preedit.isNotEmpty() && currentLine.endsWith(preedit)) {
                currentLine.substring(0, currentLine.length - preedit.length)
            } else if (currentPreedit.isNotEmpty() && currentLine.endsWith(currentPreedit)) {
                currentLine.substring(0, currentLine.length - currentPreedit.length)
            } else {
                currentLine
            }.trim()
        } else {
            ""
        }

        liveConversionJob = serviceScope.launch {
            val debounceMs = settings.liveConversionDebounceMs.toLong()
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            if (!isActive || queryId != currentLiveConversionQueryId) return@launch

            val (candidates, topCandidate) = withContext(Dispatchers.Default) {
                if (!coroutineContext.isActive || queryId != currentLiveConversionQueryId) {
                    return@withContext Pair(emptyList(), "")
                }
                val candidates = fetchCandidates(preedit, queryId, leftContext)
                val topCandidate = candidates.firstOrNull() ?: preedit
                Pair(candidates, topCandidate)
            }

            if (isActive && queryId == currentLiveConversionQueryId && currentPreedit == preedit) {
                val pureTopCandidate = topCandidate.extractPureCandidate()
                currentLiveCandidate = pureTopCandidate
                val detailStr = if (showCandidateDetails) " (推論: Live, 候補数: ${candidates.size})" else ""
                val displayTopCandidate = if (settings.isShowCandidateAnnotationsEnabled) topCandidate else pureTopCandidate
                composingTextView.text = getStatusText(preedit, "$displayTopCandidate${if (isKatakanaMode) " [カタカナ]" else ""}$detailStr")
                currentInputConnection?.setComposingText(pureTopCandidate, 1)
                renderCandidates(preedit, candidates)
            }
        }
    }

    private fun triggerSuggestFetch(preedit: String) {
        cancelLiveConversion()
        if (preedit.isEmpty()) {
            clearCandidateBar()
            return
        }
        val queryId = ++currentLiveConversionQueryId

        val leftContext = if (settings.useContext) {
            val rawContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars + preedit.length + 10, 0)?.toString() ?: ""
            val currentLine = rawContext.substringAfterLast('\n').substringAfterLast('\r')
            if (preedit.isNotEmpty() && currentLine.endsWith(preedit)) {
                currentLine.substring(0, currentLine.length - preedit.length)
            } else if (currentPreedit.isNotEmpty() && currentLine.endsWith(currentPreedit)) {
                currentLine.substring(0, currentLine.length - currentPreedit.length)
            } else {
                currentLine
            }.trim()
        } else {
            ""
        }

        liveConversionJob = serviceScope.launch {
            val debounceMs = settings.liveConversionDebounceMs.toLong()
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            if (!isActive || queryId != currentLiveConversionQueryId) return@launch

            val candidates = withContext(Dispatchers.Default) {
                if (!coroutineContext.isActive || queryId != currentLiveConversionQueryId) {
                    return@withContext emptyList<String>()
                }
                fetchCandidates(preedit, queryId, leftContext)
            }

            if (isActive && queryId == currentLiveConversionQueryId && currentPreedit == preedit) {
                currentLiveCandidate = null
                renderCandidates(preedit, candidates)
            }
        }
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
            if (currentPreedit.isNotEmpty() || romajiBuffer.isNotEmpty()) {
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

        romajiBuffer += if (isShift) key.uppercase(Locale.getDefault()) else key.lowercase(Locale.getDefault())
        val converted = engine.romajiToHiragana(romajiBuffer)
        currentPreedit = converted
        val displayPreedit = currentPreedit
        currentLiveCandidate = null
        currentInputConnection?.setComposingText(displayPreedit, 1)
        if (!settings.isLiveConversionEnabled && ::composingTextView.isInitialized) {
            composingTextView.text = getStatusText(displayPreedit)
        }
        if (settings.isLiveConversionEnabled) {
            triggerLiveConversion(displayPreedit)
        } else if (settings.isShowPhysicalKeyboardSuggestCandidatesEnabled) {
            triggerSuggestFetch(displayPreedit)
        } else {
            clearCandidateBar()
        }
    }

    private fun onKanaInput(char: String) {
        if (isEnglishMode || NikoKeyboardLayout.isDirectCommit(char)) {
            if (currentPreedit.isNotEmpty() || romajiBuffer.isNotEmpty()) {
                commitCurrentHiragana()
            }
            currentInputConnection?.commitText(char, 1)
            return
        }
        if (selectedCandidateIndex >= 0) {
            selectedCandidateIndex = -1
        }
        romajiBuffer = ""
        currentPreedit += char
        val displayPreedit = currentPreedit
        currentLiveCandidate = null
        currentInputConnection?.setComposingText(displayPreedit, 1)
        if (!settings.isLiveConversionEnabled && ::composingTextView.isInitialized) {
            composingTextView.text = getStatusText(displayPreedit)
        }
        if (settings.isLiveConversionEnabled) {
            triggerLiveConversion(displayPreedit)
        } else if (settings.isShowPhysicalKeyboardSuggestCandidatesEnabled) {
            triggerSuggestFetch(displayPreedit)
        } else {
            clearCandidateBar()
        }
    }

    private fun onNikoTouchToggle() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (textBefore.isNotEmpty() && currentPreedit.isEmpty()) {
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

        if (currentPreedit.isNotEmpty()) {
            val lastChar = currentPreedit.last().toString()
            val toggled = NikoKeyboardLayout.toggleChar(lastChar)
            if (lastChar != toggled) {
                currentPreedit = currentPreedit.dropLast(1) + toggled
                currentInputConnection?.setComposingText(currentPreedit, 1)
                if (!settings.isLiveConversionEnabled && ::composingTextView.isInitialized) {
                    composingTextView.text = getStatusText(currentPreedit)
                }
                if (settings.isLiveConversionEnabled) {
                    triggerLiveConversion(currentPreedit)
                } else if (settings.isShowPhysicalKeyboardSuggestCandidatesEnabled) {
                    triggerSuggestFetch(currentPreedit)
                } else {
                    clearCandidateBar()
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
            romajiBuffer = romajiBuffer.dropLast(1)
            currentPreedit = if (romajiBuffer.isEmpty()) "" else engine.romajiToHiragana(romajiBuffer)
        } else if (currentPreedit.isNotEmpty()) {
            currentPreedit = currentPreedit.dropLast(1)
        }

        if (currentPreedit.isEmpty() && romajiBuffer.isEmpty()) {
            romajiBuffer = ""
            currentInputConnection?.finishComposingText()
            cancelLiveConversion()
            clearComposingState()
        } else {
            currentLiveCandidate = null
            currentInputConnection?.setComposingText(currentPreedit, 1)
            if (!settings.isLiveConversionEnabled && ::composingTextView.isInitialized) {
                composingTextView.text = getStatusText(currentPreedit)
            }
            if (settings.isLiveConversionEnabled) {
                triggerLiveConversion(currentPreedit)
            } else if (settings.isShowPhysicalKeyboardSuggestCandidatesEnabled) {
                triggerSuggestFetch(currentPreedit)
            } else {
                clearCandidateBar()
            }
            return
        }
        val selectedText = currentInputConnection?.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            currentInputConnection?.commitText("", 1)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun onDeleteForward() {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        } else if (hasActiveInput) {
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
        } else if (hasActiveInput) {
            val query = currentActiveQuery
            val combinedCandidates = fetchCandidates(query)
            renderCandidates(query, combinedCandidates)
            if (currentCandidates.isNotEmpty()) {
                selectedCandidateIndex = 0
                updateSelectionPreview()
            }
        } else {
            currentInputConnection?.commitText(getSpaceString(), 1)
        }
    }

    private fun onHenkanKey() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onSpaceKey()
        } else {
            if (!reconvert()) {
                currentInputConnection?.commitText(getSpaceString(), 1)
            }
        }
    }

    private fun onKanaKey() {
        if (hasActiveInput) {
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
        if (hasActiveInput) {
            commitCurrentHiragana()
        }
        inputMode = InputMode.ALPHANUMERIC
        clearComposingState()
        if (::karukanInputView.isInitialized) {
            karukanInputView.setInputMode(inputMode)
        }
    }

    private fun onFullWidthSpace() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onPreviousCandidate()
        } else {
            currentInputConnection?.commitText("　", 1)
        }
    }

    private fun onCommitKey() {
        if (selectedCandidateIndex in currentCandidates.indices) {
            val hiragana = currentActiveQuery
            onCandidateSelected(hiragana, currentCandidates[selectedCandidateIndex])
        } else if (hasActiveInput) {
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
        } else if (hasActiveInput) {
            clearComposingState()
        }
    }

    private fun onTabKey(isShift: Boolean = false) {
        if (selectedCandidateIndex >= 0) {
            if (isShift) onPreviousCandidate() else onNextCandidate()
        } else if (hasActiveInput) {
            performConversionNoLearning()
        } else {
            val metaState = if (isShift) KeyEvent.META_SHIFT_ON else 0
            val eventTime = SystemClock.uptimeMillis()
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, metaState))
            currentInputConnection?.sendKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, metaState))
        }
    }

    private fun onToggleLanguageMode() {
        if (hasActiveInput) {
            commitCurrentHiragana()
        }
        val isEng = inputMode != InputMode.HIRAGANA
        inputMode = if (isEng) InputMode.HIRAGANA else InputMode.ALPHANUMERIC
        clearComposingState()
        if (::composingTextView.isInitialized) {
            composingTextView.text = if (isEnglishMode) "en" else "jp"
        }
        if (::karukanInputView.isInitialized) {
            karukanInputView.setInputMode(inputMode)
            karukanInputView.updateFloatingOverlay()
        }
    }

    private fun onDpadLeft(isShift: Boolean = false) {
        if (selectedCandidateIndex >= 0) {
            onEscape()
        }
        val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0)
        if (textBefore.isNullOrEmpty()) {
            return
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
        val textAfter = currentInputConnection?.getTextAfterCursor(1, 0)
        if (textAfter.isNullOrEmpty()) {
            return
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

    private fun getStatusText(preedit: String, extraInfo: String? = null): String {
        val katakanaTag = if (isKatakanaMode) " [カタカナ]" else ""
        val romajiPart = if (romajiBuffer.isNotEmpty()) " (ローマ字: $romajiBuffer)" else ""
        return if (extraInfo != null) {
            "かな$romajiPart: $preedit → $extraInfo$katakanaTag"
        } else {
            if (preedit.isEmpty()) {
                if (isEnglishMode) "en" else "jp"
            } else {
                "かな$romajiPart: $preedit$katakanaTag"
            }
        }
    }

    private fun updateComposingText() {
        requestCursorUpdates()
        val hasInput = hasActiveInput || selectedCandidateIndex >= 0
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateNikoInputState(hasInput)
        }
        if (isEnglishMode) {
            if (::engine.isInitialized && engine.isPreparing && !hasActiveInput) {
                composingTextView.text = engine.currentLoadStatus
            } else {
                composingTextView.text = "en"
            }
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            return
        }

        if (!hasActiveInput) {
            if (::engine.isInitialized && engine.isPreparing) {
                composingTextView.text = engine.currentLoadStatus
            } else {
                composingTextView.text = if (isEnglishMode) "en" else "jp"
            }
            currentLiveCandidate = null
            currentInputConnection?.setComposingText("", 0)
            currentInputConnection?.finishComposingText()
            clearCandidateBar()
            return
        }
        composingTextView.text = getStatusText(currentActiveQuery)
    }

    private fun cancelLiveConversion() {
        liveConversionJob?.cancel()
        liveConversionJob = null
        currentLiveCandidate = null
        if (currentLiveConversionQueryId > 0) {
            engine.cancelQuery(currentLiveConversionQueryId)
        }
    }

    private fun fetchCandidates(hiragana: String, queryId: Long = 0, precomputedLeftContext: String? = null): List<String> {
        if (hiragana.isEmpty()) return emptyList()

        val leftContext = precomputedLeftContext ?: if (settings.useContext) {
            val rawContext = currentInputConnection?.getTextBeforeCursor(settings.contextChars + hiragana.length + 10, 0)?.toString() ?: ""
            val currentLine = rawContext.substringAfterLast('\n').substringAfterLast('\r')
            if (hiragana.isNotEmpty() && currentLine.endsWith(hiragana)) {
                currentLine.substring(0, currentLine.length - hiragana.length)
            } else if (currentPreedit.isNotEmpty() && currentLine.endsWith(currentPreedit)) {
                currentLine.substring(0, currentLine.length - currentPreedit.length)
            } else {
                currentLine
            }.trim()
        } else {
            ""
        }

        val nativeFilter = when (activeSourceFilter) {
            SourceFilter.ALL -> 0
            SourceFilter.LEARNING -> 1
            SourceFilter.DICTIONARY -> 2
            SourceFilter.AI -> 3
            SourceFilter.SYMBOL -> 4
        }

        val rawCandidates = engine.convert(hiragana, 50, leftContext, queryId, nativeFilter)
        return if (settings.isShowCandidateAnnotationsEnabled) {
            rawCandidates
        } else {
            rawCandidates.map { it.extractPureCandidate() }.distinct()
        }
    }

    private fun performConversion() {
        if (isEnglishMode) return
        if (!hasActiveInput) return

        val hiragana = currentActiveQuery
        val combinedCandidates = fetchCandidates(hiragana)

        renderCandidates(hiragana, combinedCandidates)
        if (currentCandidates.isNotEmpty()) {
            selectedCandidateIndex = 0
            updateSelectionPreview()
        }
    }

    private fun performConversionNoLearning() {
        if (isEnglishMode || !hasActiveInput) return
        val hiragana = currentActiveQuery
        val candidates = fetchCandidates(hiragana)
        selectedCandidateIndex = 0
        renderCandidates(hiragana, candidates)
        updateSelectionPreview()
    }

    private fun convertPreeditTo(convertedText: String) {
        if (!hasActiveInput) return
        val hiragana = currentActiveQuery

        currentCandidates = listOf(convertedText)
        selectedCandidateIndex = 0
        composingTextView.text = getStatusText(hiragana, "$convertedText [1/1]")
        currentInputConnection?.setComposingText(convertedText, 1)
        if (::karukanInputView.isInitialized) {
            karukanInputView.updateCandidates(hiragana, currentCandidates, selectedCandidateIndex)
        }
    }

    private fun convertHiragana() {
        if (!hasActiveInput) return
        val hiragana = currentActiveQuery
        convertPreeditTo(hiragana)
    }

    private fun commitKatakanaOrToggleMode() {
        if (hasActiveInput) {
            val hiragana = currentActiveQuery
            val katakana = engine.hiraganaToKatakana(hiragana)
            convertPreeditTo(katakana)
        } else {
            isKatakanaMode = !isKatakanaMode
            updateComposingText()
        }
    }

    private fun fixChunkPreedit() {
        if (!hasActiveInput && romajiBuffer.isEmpty()) return
        commitCurrentHiragana()
    }

    private fun commitHankakuKatakana() {
        if (!hasActiveInput) return
        val hiragana = currentActiveQuery
        val katakana = engine.hiraganaToKatakana(hiragana)
        val hankakuKatakana = toHankakuKatakana(katakana)
        convertPreeditTo(hankakuKatakana)
    }

    private fun commitZenkakuAlphanumeric() {
        if (!hasActiveInput) return
        val raw = currentPreedit
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
        if (!hasActiveInput) return
        val raw = currentPreedit
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
        if (hasActiveInput || selectedCandidateIndex >= 0) return false
        if (lastCommittedSurface.isEmpty() || lastCommittedReading.isEmpty()) return false

        val surfaceLength = lastCommittedSurface.length
        currentInputConnection?.deleteSurroundingText(surfaceLength, 0)

        val readingToRestore = lastCommittedReading
        lastCommittedSurface = ""
        lastCommittedReading = ""

        currentPreedit = readingToRestore
        currentInputConnection?.setComposingText(currentPreedit, 1)
        updateComposingText()
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Candidate Rendering & Filtering
    // ---------------------------------------------------------------------------------------------

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

        val allCandidates = mutableListOf<String>()
        if (candidatesToProcess.isNotEmpty()) {
            allCandidates.addAll(candidatesToProcess)
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
            val hiragana = currentActiveQuery
            val selectedCand = currentCandidates[selectedCandidateIndex]
            val pureCand = selectedCand.extractPureCandidate()
            val filterTag = if (activeSourceFilter != SourceFilter.ALL) " [${getSourceFilterLabel(activeSourceFilter)}]" else ""
            composingTextView.text = getStatusText(hiragana, "$pureCand  [${selectedCandidateIndex + 1}/${currentCandidates.size}]$filterTag")
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
            if (hasActiveInput) {
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
            val hiragana = currentActiveQuery
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
        currentPreedit = ""
        romajiBuffer = ""
        currentLiveCandidate = null
        selectedCandidateIndex = -1
        activeSourceFilter = SourceFilter.ALL
        currentCandidates = emptyList()
        isLiveConversionPreviewActive = false
        currentInputConnection?.finishComposingText()
        updateComposingText()
        clearCandidateBar()
        engine.resetEngine()
    }

    private fun commitCurrentHiragana() {
        if (romajiBuffer.isNotEmpty()) {
            val flushed = engine.romajiToHiraganaFlush(romajiBuffer)
            if (flushed.isNotEmpty()) {
                currentPreedit = flushed
            }
            romajiBuffer = ""
        }
        if (!hasActiveInput) return
        val hiragana = currentActiveQuery
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
        val isComposing = hasActiveInput || selectedCandidateIndex >= 0
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
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun undoEditingArea() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
    }

    private fun cutEditingArea() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.cut)
    }

    private fun copyEditingArea() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.copy)
    }

    private fun pasteEditingArea() {
        if (hasActiveInput || selectedCandidateIndex >= 0) {
            onEscape()
        }
        currentInputConnection?.performContextMenuAction(android.R.id.paste)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: String & Character Utilities
    // ---------------------------------------------------------------------------------------------

    private fun toHankakuKatakana(str: String): String = engine.toHankakuKatakana(str)

    private fun toZenkakuAlphanumeric(str: String): String = engine.toZenkakuAlphanumeric(str)

    private fun isPairedBracket(text: String): Boolean = TextTransformUtils.isPairedBracket(text)
}
