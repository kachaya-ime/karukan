package io.github.kachaya.karukan

import android.widget.LinearLayout

/**
 * Palm OS Graffiti (unistroke) 手書き入力画面キーボードのプロバイダ兼レイアウト生成クラス。
 * 左右に補助キー列を縦配置し、中央に Graffiti 手書きエリア (GraffitiAreaView) を配置します。
 */
object GraffitiKeyboardLayout : KeyboardLayoutProvider {

    // ---------------------------------------------------------------------------------------------
    // Region: Container & Input State Management
    // ---------------------------------------------------------------------------------------------

    override val layoutId: String = "graffiti"
    override val rowCount: Int = 4

    private var activeSideButtons: List<KeyButton>? = null
    private var lastParamsRef: java.lang.ref.WeakReference<KeyboardCreateParams>? = null

    override fun createContainer(params: KeyboardCreateParams): LinearLayout {
        lastParamsRef = java.lang.ref.WeakReference(params)
        return createContainerInternal(params)
    }

    override fun updateInputMode(inputMode: InputMode) {
        val isDark = lastParamsRef?.get()?.isDarkSupplier?.invoke() ?: false
        activeSideButtons?.forEach { btn ->
            btn.updateInputMode(inputMode, isDark)
        }
    }

    override fun updateLanguageMode(isEnglishMode: Boolean) {
        val isDark = lastParamsRef?.get()?.isDarkSupplier?.invoke() ?: false
        activeSideButtons?.forEach { btn ->
            btn.updateLanguageMode(isEnglishMode, isDark)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Layout Construction & Key Handling
    // ---------------------------------------------------------------------------------------------

    private fun createContainerInternal(params: KeyboardCreateParams): LinearLayout {
        val context = params.context
        GraffitiStrokeRecognizer.loadTemplatesFromContext(context)

        val isDarkSupplier = params.isDarkSupplier
        val keyButtonHeightSupplier = params.keyButtonHeightSupplier
        val listener = params.listener

        var currentMode: QwertyMode = QwertyMode.NORMAL
        val sideButtons = mutableListOf<KeyButton>()

        fun updateSideButtonsMode(mode: QwertyMode) {
            for (btn in sideButtons) {
                btn.updateMode(mode, isDarkSupplier())
            }
        }

        fun handleStrokeResult(result: GraffitiStrokeRecognizer.RecognitionResult) {
            if (result is GraffitiStrokeRecognizer.RecognitionResult.None) return
            listener.onKeyFeedback()
            val isCtrlMode = currentMode == QwertyMode.CTRL

            when (result) {
                is GraffitiStrokeRecognizer.RecognitionResult.CharResult -> {
                    if (isCtrlMode) {
                        listener.onCtrlShortcut(result.char)
                    } else {
                        listener.onKeyPress(result.char)
                    }
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Space -> {
                    if (isCtrlMode) {
                        listener.onToggleLanguageMode()
                    } else {
                        listener.onSpaceOrConvert()
                    }
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Backspace -> {
                    if (isCtrlMode) {
                        listener.onCtrlBackspace()
                    } else {
                        listener.onBackspace()
                    }
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Return -> {
                    listener.onCommit()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.DpadLeft -> {
                    listener.onDpadLeft()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.DpadRight -> {
                    listener.onDpadRight()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.DpadUp -> {
                    listener.onDpadUp()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.DpadDown -> {
                    listener.onDpadDown()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Ctrl -> {
                    currentMode = if (isCtrlMode) QwertyMode.NORMAL else QwertyMode.CTRL
                    updateSideButtonsMode(currentMode)
                    return
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Tab -> {
                    listener.onTab()
                }
                is GraffitiStrokeRecognizer.RecognitionResult.Cancel -> {
                    listener.onEsc()
                }
                else -> {}
            }

            if (isCtrlMode) {
                currentMode = QwertyMode.NORMAL
                updateSideButtonsMode(currentMode)
            }
        }

        fun dispatchKeyAction(type: KeyType) {
            when (type) {
                KeyType.BACKSPACE -> listener.onBackspace()
                KeyType.DELETE -> listener.onDelete()
                KeyType.SPACE -> listener.onSpaceOrConvert()
                KeyType.ENTER -> listener.onCommit()
                KeyType.LANG -> listener.onToggleLanguageMode()
                KeyType.CHUNK_FIX -> listener.onFixChunk()
                KeyType.EMOJI -> listener.onToggleEmojiPalette()
                KeyType.DPAD_LEFT -> listener.onDpadLeft()
                KeyType.DPAD_RIGHT -> listener.onDpadRight()
                KeyType.DPAD_UP -> listener.onDpadUp()
                KeyType.DPAD_DOWN -> listener.onDpadDown()
                KeyType.TAB -> listener.onTab()
                KeyType.KATAKANA -> listener.onKatakana()
                KeyType.ESC -> listener.onEsc()
                else -> {}
            }
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val totalHeight = keyButtonHeightSupplier() * 4
        val sideKeyWidth = (56 * context.resources.displayMetrics.density).toInt()

        val leftKeySpecs = listOf(
            KeySpec("esc", KeyType.ESC),
            KeySpec("←", KeyType.DPAD_LEFT, repeatable = true),
            KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24),
            KeySpec(
                normalText = "あa",
                shiftText = "あa",
                symbolText = "tab",
                normalType = KeyType.LANG,
                shiftType = KeyType.LANG,
                symbolType = KeyType.TAB
            )
        )
        val leftKeyHeight = totalHeight / leftKeySpecs.size

        val leftContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(sideKeyWidth, totalHeight)
        }
        for (spec in leftKeySpecs) {
            val btn = params.createKeyButton(spec, leftKeyHeight).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            sideButtons.add(btn)
            leftContainer.addView(btn)
        }
        rootLayout.addView(leftContainer)

        lateinit var graffitiAreaView: GraffitiAreaView
        graffitiAreaView = GraffitiAreaView(
            context = context,
            isDarkSupplier = isDarkSupplier,
            onStrokeRecognized = { result ->
                handleStrokeResult(result)
            },
            onStrokeHelpRequested = {
                GraffitiHelpDialog.show(graffitiAreaView, isDarkSupplier())
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                totalHeight,
                1f
            )
        }
        rootLayout.addView(graffitiAreaView)

        val rightKeySpecs = listOf(
            KeySpec("⌫", normalType = KeyType.BACKSPACE, repeatable = true),
            KeySpec("→", KeyType.DPAD_RIGHT, repeatable = true),
            KeySpec("⌴", KeyType.SPACE),
            KeySpec("⏎", KeyType.ENTER)
        )
        val rightKeyHeight = totalHeight / rightKeySpecs.size

        val rightContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(sideKeyWidth, totalHeight)
        }
        for (spec in rightKeySpecs) {
            val btn = params.createKeyButton(spec, rightKeyHeight).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            sideButtons.add(btn)
            rightContainer.addView(btn)
        }
        rootLayout.addView(rightContainer)

        for (btn in sideButtons) {
            btn.setOnClickListener {
                listener.onKeyFeedback()
                val isCtrlMode = currentMode == QwertyMode.CTRL
                if (isCtrlMode) {
                    when (btn.currentType) {
                        KeyType.CHARACTER -> {
                            listener.onCtrlShortcut(btn.spec.normalText)
                        }
                        KeyType.BACKSPACE -> {
                            listener.onCtrlBackspace()
                        }
                        KeyType.CTRL -> {
                            // Ctrl キー再タップで解除
                        }
                        else -> {
                            dispatchKeyAction(btn.currentType)
                        }
                    }
                    currentMode = QwertyMode.NORMAL
                    updateSideButtonsMode(currentMode)
                } else {
                    if (btn.currentType == KeyType.CTRL) {
                        currentMode = QwertyMode.CTRL
                        updateSideButtonsMode(currentMode)
                    } else {
                        dispatchKeyAction(btn.currentType)
                    }
                }
            }
        }

        activeSideButtons = sideButtons
        updateSideButtonsMode(currentMode)
        return rootLayout
    }
}
