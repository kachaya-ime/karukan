package io.github.kachaya.karukan

import android.widget.LinearLayout

/**
 * 2次元の KeySpec テーブルから標準的なグリッド配置キーボードを構築するプロバイダ。
 * QWERTY, Tablet, Builtin 等の標準キーボードで共通利用されます。
 */
class StandardGridKeyboardProvider(
    override val layoutId: String,
    override val rowCount: Int = 5,
    private val layoutSpecSupplier: () -> List<List<KeySpec>>
) : KeyboardLayoutProvider {

    // ---------------------------------------------------------------------------------------------
    // Region: Properties & Setup
    // ---------------------------------------------------------------------------------------------

    private var activeButtons: List<KeyButton>? = null
    private var currentMode: QwertyMode = QwertyMode.NORMAL
    private var lastParamsRef: java.lang.ref.WeakReference<KeyboardCreateParams>? = null

    // ---------------------------------------------------------------------------------------------
    // Region: Container Creation & Layout Mapping
    // ---------------------------------------------------------------------------------------------

    override fun createContainer(params: KeyboardCreateParams): LinearLayout {
        lastParamsRef = java.lang.ref.WeakReference(params)
        currentMode = QwertyMode.NORMAL
        val context = params.context

        val keyboardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val allButtons = mutableListOf<KeyButton>()
        val layoutSpec = layoutSpecSupplier()

        for (rowSpec in layoutSpec) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (keySpec in rowSpec) {
                val btn = params.createKeyButton(keySpec)
                rowLayout.addView(btn)
                allButtons.add(btn)
            }
            keyboardContainer.addView(rowLayout)
        }

        activeButtons = allButtons

        fun updateAllModes() {
            val isDarkCurrent = params.isDarkSupplier()
            for (btn in allButtons) {
                btn.updateMode(currentMode, isDarkCurrent)
            }
        }

        // -----------------------------------------------------------------------------------------
        // Region: Key Action Dispatcher
        // -----------------------------------------------------------------------------------------

        val keyActionTable: Map<KeyType, (KeyButton) -> Unit> = mapOf(
            KeyType.CHARACTER to { btn ->
                val isShift = currentMode == QwertyMode.SHIFT
                params.listener.onKeyPress(btn.text.toString(), isShift = isShift)
                if (currentMode == QwertyMode.SHIFT) {
                    currentMode = QwertyMode.NORMAL
                    updateAllModes()
                }
            },
            KeyType.BACKSPACE to { _ -> params.listener.onBackspace() },
            KeyType.DELETE to { _ -> params.listener.onDelete() },
            KeyType.SPACE to { _ -> params.listener.onSpaceOrConvert() },
            KeyType.ENTER to { _ -> params.listener.onCommit() },
            KeyType.SHIFT to { _ ->
                currentMode = when (currentMode) {
                    QwertyMode.NORMAL -> QwertyMode.SHIFT
                    QwertyMode.SHIFT -> QwertyMode.NORMAL
                    QwertyMode.SYMBOL -> QwertyMode.NORMAL
                    QwertyMode.CTRL -> QwertyMode.SHIFT
                }
                updateAllModes()
            },
            KeyType.SYMBOL to { _ -> currentMode = QwertyMode.SYMBOL; updateAllModes() },
            KeyType.NORMAL to { _ -> currentMode = QwertyMode.NORMAL; updateAllModes() },
            KeyType.CTRL to { _ -> currentMode = QwertyMode.CTRL; updateAllModes() },
            KeyType.LANG to { _ -> params.listener.onToggleLanguageMode() },
            KeyType.CHUNK_FIX to { _ -> params.listener.onFixChunk() },
            KeyType.EMOJI to { _ -> params.listener.onToggleEmojiPalette() },
            KeyType.DPAD_LEFT to { _ -> params.listener.onDpadLeft() },
            KeyType.DPAD_RIGHT to { _ -> params.listener.onDpadRight() },
            KeyType.DPAD_UP to { _ -> params.listener.onDpadUp() },
            KeyType.DPAD_DOWN to { _ -> params.listener.onDpadDown() },
            KeyType.TAB to { _ -> params.listener.onTab() },
            KeyType.KATAKANA to { _ -> params.listener.onKatakana() },
            KeyType.GAP to { _ -> },
            KeyType.ESC to { _ -> params.listener.onEsc() }
        )

        for (btn in allButtons) {
            if (btn.currentType == KeyType.GAP) {
                btn.isClickable = false
                btn.isFocusable = false
                btn.isEnabled = false
                continue
            }
            btn.setOnClickListener {
                params.listener.onKeyFeedback()
                if (currentMode == QwertyMode.CTRL) {
                    when (btn.currentType) {
                        KeyType.CHARACTER -> {
                            params.listener.onCtrlShortcut(btn.spec.normalText)
                            currentMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        KeyType.BACKSPACE -> {
                            params.listener.onCtrlBackspace()
                            currentMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        KeyType.CTRL -> {
                            currentMode = QwertyMode.NORMAL
                            updateAllModes()
                        }

                        else -> {
                            keyActionTable[btn.currentType]?.invoke(btn)
                            currentMode = QwertyMode.NORMAL
                            updateAllModes()
                        }
                    }
                } else {
                    keyActionTable[btn.currentType]?.invoke(btn)
                }
            }
        }

        updateAllModes()
        return keyboardContainer
    }

    override fun updateInputMode(inputMode: InputMode) {
        val isDark = lastParamsRef?.get()?.isDarkSupplier?.invoke() ?: false
        activeButtons?.forEach { btn ->
            btn.updateInputMode(inputMode, isDark)
        }
    }

    override fun updateLanguageMode(isEnglishMode: Boolean) {
        val isDark = lastParamsRef?.get()?.isDarkSupplier?.invoke() ?: false
        activeButtons?.forEach { btn ->
            btn.updateLanguageMode(isEnglishMode, isDark)
        }
    }
}
