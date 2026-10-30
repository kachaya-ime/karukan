package io.github.kachaya.karukan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * QWERTY, Tablet, ニコタッチキーボードの構築オーケストレーションを行う Builder クラス
 */
class KeyboardLayoutBuilder(
    private val context: Context,
    private val isDarkThemeSupplier: () -> Boolean,
    private val keyButtonHeightSupplier: () -> Int,
    private val isEnglishModeSupplier: () -> Boolean = { false },
    private val inputModeSupplier: () -> InputMode = { if (isEnglishModeSupplier()) InputMode.ALPHANUMERIC else InputMode.HIRAGANA },
    private val listener: KeyboardEventListener
) {
    interface KeyboardEventListener : io.github.kachaya.karukan.KeyboardEventListener

    private var currentQwertyMode: QwertyMode = QwertyMode.NORMAL
    private var activeButtons: List<KeyButton>? = null

    private var pendingNikoRowKey: Int? = null
    private var currentHasInput: Boolean = false
    var onContainerSwapRequested: ((LinearLayout) -> Unit)? = null
    private var escButton: KeyButton? = null
    private val nikoKeyButtons = mutableMapOf<Int, KeyButton>()
    private var currentInputMode: InputMode = inputModeSupplier()

    fun updateLanguageMode(isEnglishMode: Boolean) {
        activeButtons?.forEach { btn ->
            btn.updateLanguageMode(isEnglishMode, isDark)
            btn.updateInputMode(if (isEnglishMode) InputMode.ALPHANUMERIC else InputMode.HIRAGANA, isDark)
        }
    }

    fun updateInputMode(inputMode: InputMode) {
        currentInputMode = inputMode
        activeButtons?.forEach { btn ->
            btn.updateInputMode(inputMode, isDark)
            btn.updateLanguageMode(inputMode != InputMode.HIRAGANA, isDark)
        }
    }

    private val isDark: Boolean
        get() = isDarkThemeSupplier()

    private val keyTextColor: Int
        get() = KeyboardColors.get(isDark).keyText

    private fun createKeyButton(spec: KeySpec): KeyButton {
        return KeyButton(
            context = context,
            spec = spec,
            keyHeight = keyButtonHeightSupplier(),
            keyTextColor = keyTextColor,
            onFeedback = { listener.onKeyFeedback() }
        ).apply {
            updateLanguageMode(isEnglishModeSupplier(), isDark)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatableTouchListener(btn: KeyButton) {
        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null
        var isHeld: Boolean

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    btn.performClick()

                    runnable = object : Runnable {
                        override fun run() {
                            if (isHeld) {
                                btn.performClick()
                                handler.postDelayed(this, 70) // 繰り返し間隔 (70ms)
                            }
                        }
                    }
                    handler.postDelayed(runnable!!, 400) // 初回長押しディレイ (400ms)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHeld = false
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = null
                    true
                }

                else -> false
            }
        }
    }

    private fun buildKeyboardLayout(
        layoutSpec: List<List<KeySpec>>,
        onButtonCreated: ((KeySpec, KeyButton) -> Unit)? = null
    ): LinearLayout {
        val keyboardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 0)
            }
        }

        val allButtons = mutableListOf<KeyButton>()
        for (rowSpec in layoutSpec) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 0)
                }
            }
            for (keySpec in rowSpec) {
                val btn = createKeyButton(keySpec)
                btn.updateInputMode(currentInputMode, isDark)
                rowLayout.addView(btn)
                allButtons.add(btn)
                onButtonCreated?.invoke(keySpec, btn)
            }
            keyboardContainer.addView(rowLayout)
        }

        setupGlobalListeners(allButtons)
        return keyboardContainer
    }

    fun createQwertyKeyboardContainer(): LinearLayout {
        currentQwertyMode = QwertyMode.NORMAL
        return buildKeyboardLayout(QwertyKeyboardLayout.getLayoutSpec())
    }

    fun createTabletKeyboardContainer(): LinearLayout {
        currentQwertyMode = QwertyMode.NORMAL
        return buildKeyboardLayout(TabletKeyboardLayout.getLayoutSpec())
    }

    fun createNikoKeyboardContainer(): LinearLayout {
        currentInputMode = inputModeSupplier()
        return when (currentInputMode) {
            InputMode.NUMERIC -> createNikoNumericKeyboardContainer()
            InputMode.ALPHANUMERIC -> createNikoTouchKeyboardContainer()
            InputMode.HIRAGANA -> createNikoTouchKeyboardContainer()
        }
    }

    fun updateNikoInputState(hasInput: Boolean) {
        if (currentHasInput == hasInput) return
        currentHasInput = hasInput
        if (pendingNikoRowKey == null) {
            val nikoContainer = createNikoKeyboardContainer()
            onContainerSwapRequested?.invoke(nikoContainer)
        }
    }

    fun createNikoNumericKeyboardContainer(): LinearLayout {
        pendingNikoRowKey = null
        nikoKeyButtons.clear()

        return buildKeyboardLayout(
            layoutSpec = NikoKeyboardLayout.getNumericLayoutSpec(currentHasInput)
        ) { keySpec, btn ->
            keySpec.normalType.nikoKeyIndex?.let { index ->
                nikoKeyButtons[index] = btn
            }
        }
    }

    fun createNikoTouchKeyboardContainer(): LinearLayout {
        pendingNikoRowKey = null
        nikoKeyButtons.clear()
        escButton = null

        val isHiragana = currentInputMode == InputMode.HIRAGANA
        return buildKeyboardLayout(
            layoutSpec = NikoKeyboardLayout.getLayoutSpec(currentHasInput, isHiragana)
        ) { keySpec, btn ->
            if (keySpec.normalType == KeyType.ESC) {
                escButton = btn
            }
            keySpec.normalType.nikoKeyIndex?.let { index ->
                nikoKeyButtons[index] = btn
            }
        }
    }

    private fun setupGlobalListeners(allButtons: List<KeyButton>) {
        activeButtons = allButtons
        fun updateAllModes() {
            for (btn in allButtons) {
                btn.updateMode(currentQwertyMode, isDark)
            }
        }

        val keyActionTable: Map<KeyType, (KeyButton) -> Unit> = mapOf(
            KeyType.CHARACTER to { btn ->
                val isShift = currentQwertyMode == QwertyMode.SHIFT
                listener.onKeyPress(btn.text.toString(), isShift = isShift)
                if (currentQwertyMode == QwertyMode.SHIFT) {
                    currentQwertyMode = QwertyMode.NORMAL
                    updateAllModes()
                }
            },
            KeyType.BACKSPACE to { _ -> listener.onBackspace() },
            KeyType.DELETE to { _ -> listener.onDelete() },
            KeyType.SPACE to { _ -> listener.onSpaceOrConvert() },
            KeyType.ENTER to { _ -> listener.onCommit() },
            KeyType.SHIFT to { _ ->
                currentQwertyMode = when (currentQwertyMode) {
                    QwertyMode.NORMAL -> QwertyMode.SHIFT
                    QwertyMode.SHIFT -> QwertyMode.NORMAL
                    QwertyMode.SYMBOL -> QwertyMode.NORMAL
                    QwertyMode.CTRL -> QwertyMode.SHIFT
                }
                updateAllModes()
            },
            KeyType.SYMBOL to { _ -> currentQwertyMode = QwertyMode.SYMBOL; updateAllModes() },
            KeyType.NORMAL to { _ -> currentQwertyMode = QwertyMode.NORMAL; updateAllModes() },
            KeyType.CTRL to { _ -> currentQwertyMode = QwertyMode.CTRL; updateAllModes() },
            KeyType.LANG to { _ -> listener.onToggleLanguageMode() },
            KeyType.NIKO_LANG to { _ -> listener.onNikoToggleLanguageMode() },
            KeyType.CHUNK_FIX to { _ -> listener.onFixChunk() },
            KeyType.EMOJI to { _ -> listener.onToggleEmojiPalette() },
            KeyType.DPAD_LEFT to { _ -> listener.onDpadLeft() },
            KeyType.DPAD_RIGHT to { _ -> listener.onDpadRight() },
            KeyType.DPAD_UP to { _ -> listener.onDpadUp() },
            KeyType.DPAD_DOWN to { _ -> listener.onDpadDown() },
            KeyType.TAB to { _ -> listener.onTab() },
            KeyType.KATAKANA to { _ -> listener.onKatakana() },
            KeyType.GAP to { _ -> },
            KeyType.ESC to { _ ->
                if (pendingNikoRowKey != null) {
                    pendingNikoRowKey = null
                    val firstStageContainer = createNikoTouchKeyboardContainer()
                    onContainerSwapRequested?.invoke(firstStageContainer)
                } else {
                    listener.onEsc()
                }
            },
            KeyType.NIKO_GUIDE_CHAR to { btn ->
                val char = btn.text.toString()
                if (char.isNotEmpty()) {
                    pendingNikoRowKey = null
                    val firstStageContainer = createNikoTouchKeyboardContainer()
                    onContainerSwapRequested?.invoke(firstStageContainer)
                    listener.onNikoTouchInput(char)
                }
            },
            KeyType.KEY_STAR to { _ ->
                val mode = inputModeSupplier()
                if (mode == InputMode.NUMERIC || mode == InputMode.ALPHANUMERIC) {
                    listener.onNikoTouchInput("*")
                } else {
                    listener.onNikoTouchToggle()
                }
            },
            KeyType.KEY_POUND to { _ ->
                val mode = inputModeSupplier()
                if (mode == InputMode.NUMERIC || mode == InputMode.ALPHANUMERIC) {
                    listener.onNikoTouchInput("#")
                } else {
                    listener.onToggleSymbolPalette()
                }
            }
        )

        fun dispatchKeyAction(btn: KeyButton) {
            val type = btn.currentType
            if (type.isNikoKey) {
                type.nikoKeyIndex?.let { onNikoKeyClick(it) }
                return
            }
            keyActionTable[type]?.invoke(btn)
        }

        for (btn in allButtons) {
            if (btn.currentType == KeyType.GAP) {
                btn.isClickable = false
                btn.isFocusable = false
                btn.isEnabled = false
                continue
            }
            btn.setOnClickListener {
                listener.onKeyFeedback()
                if (pendingNikoRowKey != null && btn == escButton) {
                    pendingNikoRowKey = null
                    val firstStageContainer = createNikoTouchKeyboardContainer()
                    onContainerSwapRequested?.invoke(firstStageContainer)
                    return@setOnClickListener
                }
                if (currentQwertyMode == QwertyMode.CTRL) {
                    when (btn.currentType) {
                        KeyType.CHARACTER -> {
                            listener.onCtrlShortcut(btn.spec.normalText)
                            currentQwertyMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        KeyType.BACKSPACE -> {
                            listener.onCtrlBackspace()
                            currentQwertyMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        KeyType.CTRL -> {
                            currentQwertyMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        else -> {
                            dispatchKeyAction(btn)
                            currentQwertyMode = QwertyMode.NORMAL
                            updateAllModes()
                        }
                    }
                } else {
                    dispatchKeyAction(btn)
                }
            }

            if (btn.spec.repeatable) {
                setupRepeatableTouchListener(btn)
            }
        }

        updateAllModes()
    }

    fun onNikoKeyClick(keyIdx: Int) {
        val mode = inputModeSupplier()
        if (mode == InputMode.NUMERIC) {
            val charVal = keyIdx.toString()
            listener.onNikoTouchInput(charVal)
            return
        }

        val currentRow = pendingNikoRowKey
        if (currentRow == null) {
            if (keyIdx in 0..9) {
                pendingNikoRowKey = keyIdx
                val isHiragana = currentInputMode == InputMode.HIRAGANA
                val secondStageContainer = buildKeyboardLayout(
                    NikoKeyboardLayout.getSecondStageLayoutSpec(keyIdx, isHiragana)
                ) { keySpec, btn ->
                    val char = keySpec.normalText
                    if (char.isNotEmpty() && keySpec.normalType == KeyType.CHARACTER) {
                        btn.setOnClickListener {
                            listener.onKeyFeedback()
                            pendingNikoRowKey = null
                            val firstStageContainer = createNikoTouchKeyboardContainer()
                            onContainerSwapRequested?.invoke(firstStageContainer)
                            listener.onNikoTouchInput(char)
                        }
                    } else if (keySpec.normalType == KeyType.ESC || keySpec.normalType == KeyType.BACKSPACE) {
                        btn.setOnClickListener {
                            listener.onKeyFeedback()
                            pendingNikoRowKey = null
                            val firstStageContainer = createNikoTouchKeyboardContainer()
                            onContainerSwapRequested?.invoke(firstStageContainer)
                        }
                    }
                }
                onContainerSwapRequested?.invoke(secondStageContainer)
            }
        } else {
            pendingNikoRowKey = null
            val firstStageContainer = createNikoTouchKeyboardContainer()
            onContainerSwapRequested?.invoke(firstStageContainer)
        }
    }
}