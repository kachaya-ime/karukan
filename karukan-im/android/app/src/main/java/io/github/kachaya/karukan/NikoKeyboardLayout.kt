package io.github.kachaya.karukan

import android.widget.LinearLayout

/**
 * ニコタッチ入力（12キーマトリクス入力）のキーボードレイアウト定義、
 * 文字マッピング、プロバイダ機能および 2段階状態管理を提供するオブジェクトです。
 */
object NikoKeyboardLayout : KeyboardLayoutProvider {

    // ---------------------------------------------------------------------------------------------
    // Region: Container & Input State Management
    // ---------------------------------------------------------------------------------------------

    override val layoutId: String = "niko"
    override val rowCount: Int = 4

    private var pendingNikoRowKey: Int? = null
    private var currentHasInput: Boolean = false
    private val nikoKeyButtons = mutableMapOf<Int, KeyButton>()
    private var lastParamsRef: java.lang.ref.WeakReference<KeyboardCreateParams>? = null
    private var activeButtons: List<KeyButton>? = null

    override fun createContainer(params: KeyboardCreateParams): LinearLayout {
        lastParamsRef = java.lang.ref.WeakReference(params)
        pendingNikoRowKey = null
        val mode = params.currentInputModeSupplier()
        return if (mode == InputMode.NUMERIC) {
            createNumericContainer(params)
        } else {
            createTouchContainer(params)
        }
    }

    override fun updateInputState(hasInput: Boolean) {
        if (currentHasInput == hasInput) return
        currentHasInput = hasInput
        if (pendingNikoRowKey == null) {
            lastParamsRef?.get()?.let { params ->
                val container = createContainer(params)
                params.onContainerSwapRequested?.invoke(container)
            }
        }
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

    // ---------------------------------------------------------------------------------------------
    // Region: Touch State & 2-Stage Key Press Handlers
    // ---------------------------------------------------------------------------------------------

    fun onNikoKeyClick(keyIdx: Int) {
        lastParamsRef?.get()?.let { params ->
            onNikoKeyClick(keyIdx, params)
        }
    }

    fun onNikoKeyClick(keyIdx: Int, params: KeyboardCreateParams) {
        val mode = params.currentInputModeSupplier()
        if (mode == InputMode.NUMERIC) {
            val charVal = keyIdx.toString()
            params.listener.onNikoTouchInput(charVal)
            return
        }

        val pending = pendingNikoRowKey
        if (pending == null) {
            // 1打目選択
            pendingNikoRowKey = keyIdx
            val guideList = getRowGuideChars(keyIdx)
            if (guideList != null) {
                for (i in 0..9) {
                    val guideChar = guideList.getOrElse(i) { "" }
                    nikoKeyButtons[i]?.setGuideText(guideChar)
                }
            }
            val secondStageContainer = createSecondStageContainer(keyIdx, params)
            params.onContainerSwapRequested?.invoke(secondStageContainer)
        } else {
            // 2打目確定
            pendingNikoRowKey = null
            nikoKeyButtons.values.forEach { it.clearGuideText() }
            val firstStageContainer = createTouchContainer(params)
            params.onContainerSwapRequested?.invoke(firstStageContainer)

            val guideList = getRowGuideChars(pending)
            val indexInList = if (keyIdx == 0) 9 else keyIdx - 1
            val resultChar = guideList?.getOrNull(indexInList) ?: ""
            if (resultChar.isNotEmpty()) {
                params.listener.onNikoTouchInput(resultChar)
            }
        }
    }

    private fun buildNikoContainer(
        layoutSpec: List<List<KeySpec>>,
        params: KeyboardCreateParams,
        onButtonCreated: ((KeySpec, KeyButton) -> Unit)? = null
    ): LinearLayout {
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
                onButtonCreated?.invoke(keySpec, btn)
            }
            keyboardContainer.addView(rowLayout)
        }

        activeButtons = allButtons

        fun dispatchKeyAction(btn: KeyButton) {
            val type = btn.currentType
            if (type.isNikoKey) {
                type.nikoKeyIndex?.let { onNikoKeyClick(it, params) }
                return
            }
            when (type) {
                KeyType.BACKSPACE -> params.listener.onBackspace()
                KeyType.NIKO_BACKSPACE -> {
                    pendingNikoRowKey = null
                    val firstStageContainer = createTouchContainer(params)
                    params.onContainerSwapRequested?.invoke(firstStageContainer)
                    params.listener.onBackspace()
                }

                KeyType.DELETE -> params.listener.onDelete()
                KeyType.SPACE -> params.listener.onSpaceOrConvert()
                KeyType.ENTER -> params.listener.onCommit()
                KeyType.LANG -> params.listener.onToggleLanguageMode()
                KeyType.NIKO_LANG -> params.listener.onNikoToggleLanguageMode()
                KeyType.CHUNK_FIX -> params.listener.onFixChunk()
                KeyType.EMOJI -> params.listener.onToggleEmojiPalette()
                KeyType.DPAD_LEFT -> params.listener.onDpadLeft()
                KeyType.DPAD_RIGHT -> params.listener.onDpadRight()
                KeyType.DPAD_UP -> params.listener.onDpadUp()
                KeyType.DPAD_DOWN -> params.listener.onDpadDown()
                KeyType.TAB -> params.listener.onTab()
                KeyType.KATAKANA -> params.listener.onKatakana()
                KeyType.ESC -> params.listener.onEsc()
                KeyType.NIKO_CANCEL -> {
                    pendingNikoRowKey = null
                    val firstStageContainer = createTouchContainer(params)
                    params.onContainerSwapRequested?.invoke(firstStageContainer)
                }

                KeyType.NIKO_GUIDE_CHAR -> {
                    val char = btn.text.toString()
                    if (char.isNotEmpty()) {
                        pendingNikoRowKey = null
                        val firstStageContainer = createTouchContainer(params)
                        params.onContainerSwapRequested?.invoke(firstStageContainer)
                        params.listener.onNikoTouchInput(char)
                    }
                }

                KeyType.NIKO_TOGGLE -> params.listener.onNikoTouchToggle()
                KeyType.NIKO_INPUT_STAR -> params.listener.onNikoTouchInput("*")
                KeyType.NIKO_INPUT_POUND -> params.listener.onNikoTouchInput("#")
                KeyType.NIKO_SYMBOL_PALETTE -> params.listener.onToggleSymbolPalette()
                else -> {}
            }
        }

        for (btn in allButtons) {
            if (btn.currentType == KeyType.GAP) {
                btn.isClickable = false
                btn.isFocusable = false
                btn.isEnabled = false
                continue
            }
            btn.setOnClickListener {
                params.listener.onKeyFeedback()
                dispatchKeyAction(btn)
            }
        }

        return keyboardContainer
    }

    private fun createTouchContainer(params: KeyboardCreateParams): LinearLayout {
        pendingNikoRowKey = null
        nikoKeyButtons.clear()
        val isHiragana = params.currentInputModeSupplier() == InputMode.HIRAGANA
        return buildNikoContainer(
            layoutSpec = getLayoutSpec(currentHasInput, isHiragana),
            params = params
        ) { keySpec, btn ->
            keySpec.normalType.nikoKeyIndex?.let { index ->
                nikoKeyButtons[index] = btn
            }
        }
    }

    private fun createSecondStageContainer(rowKey: Int, params: KeyboardCreateParams): LinearLayout {
        nikoKeyButtons.clear()
        val isHiragana = params.currentInputModeSupplier() == InputMode.HIRAGANA
        return buildNikoContainer(
            layoutSpec = getSecondStageLayoutSpec(rowKey, isHiragana),
            params = params
        ) { keySpec, btn ->
            keySpec.normalType.nikoKeyIndex?.let { index ->
                nikoKeyButtons[index] = btn
            }
        }
    }

    private fun createNumericContainer(params: KeyboardCreateParams): LinearLayout {
        pendingNikoRowKey = null
        nikoKeyButtons.clear()
        return buildNikoContainer(
            layoutSpec = getNumericLayoutSpec(currentHasInput),
            params = params
        ) { keySpec, btn ->
            keySpec.normalType.nikoKeyIndex?.let { index ->
                nikoKeyButtons[index] = btn
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Niko Touch Tables & Character Toggling
    // ---------------------------------------------------------------------------------------------

    private val NIKO_TOUCH_TABLE: Map<Int, List<String>> = mapOf(
        1 to listOf("あ", "い", "う", "え", "お", ".", "-", "@", "_", "1"),
        2 to listOf("か", "き", "く", "け", "こ", "[", "a", "b", "c", "2"),
        3 to listOf("さ", "し", "す", "せ", "そ", "]", "d", "e", "f", "3"),
        4 to listOf("た", "ち", "つ", "て", "と", "っ", "g", "h", "i", "4"),
        5 to listOf("な", "に", "ぬ", "ね", "の", "(", "j", "k", "l", "5"),
        6 to listOf("は", "ひ", "ふ", "へ", "ほ", ")", "m", "n", "o", "6"),
        7 to listOf("ま", "み", "む", "め", "も", "p", "q", "r", "s", "7"),
        8 to listOf("や", "ゆ", "よ", "ゃ", "ゅ", "ょ", "t", "u", "v", "8"),
        9 to listOf("ら", "り", "る", "れ", "ろ", "w", "x", "y", "z", "9"),
        0 to listOf("わ", "を", "ん", "、", "。", "ー", "・", "！", "？", "0")
    )

    private val TOGGLE_MAP: Map<String, String> = mapOf(
        "あ" to "ぁ", "ぁ" to "あ",
        "い" to "ぃ", "ぃ" to "い",
        "う" to "ぅ", "ぅ" to "ゔ", "ゔ" to "う",
        "え" to "ぇ", "ぇ" to "え",
        "お" to "ぉ", "ぉ" to "お",

        "か" to "が", "が" to "か",
        "き" to "ぎ", "ぎ" to "き",
        "く" to "ぐ", "ぐ" to "く",
        "け" to "げ", "げ" to "け",
        "こ" to "ご", "ご" to "こ",

        "さ" to "ざ", "ざ" to "さ",
        "し" to "じ", "じ" to "し",
        "す" to "ず", "ず" to "す",
        "せ" to "ぜ", "ぜ" to "せ",
        "そ" to "ぞ", "ぞ" to "そ",

        "た" to "だ", "だ" to "た",
        "ち" to "ぢ", "ぢ" to "ち",
        "つ" to "っ", "っ" to "づ", "づ" to "つ",
        "て" to "で", "で" to "て",
        "と" to "ど", "ど" to "と",

        "は" to "ば", "ば" to "ぱ", "ぱ" to "は",
        "ひ" to "び", "び" to "ぴ", "ぴ" to "ひ",
        "ふ" to "ぶ", "ぶ" to "ぷ", "ぷ" to "ふ",
        "へ" to "べ", "べ" to "ぺ", "ぺ" to "へ",
        "ほ" to "ぼ", "ぼ" to "ぽ", "ぽ" to "ほ",

        "や" to "ゃ", "ゃ" to "や",
        "ゆ" to "ゅ", "ゅ" to "ゆ",
        "よ" to "ょ", "ょ" to "よ",
        "わ" to "ゎ", "ゎ" to "わ"
    )

    fun getRowGuideChars(rowKey: Int): List<String>? = NIKO_TOUCH_TABLE[rowKey]

    fun keyCodeToNikoKeyIndex(keyCode: Int): Int? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_0 -> 0
        android.view.KeyEvent.KEYCODE_1 -> 1
        android.view.KeyEvent.KEYCODE_2 -> 2
        android.view.KeyEvent.KEYCODE_3 -> 3
        android.view.KeyEvent.KEYCODE_4 -> 4
        android.view.KeyEvent.KEYCODE_5 -> 5
        android.view.KeyEvent.KEYCODE_6 -> 6
        android.view.KeyEvent.KEYCODE_7 -> 7
        android.view.KeyEvent.KEYCODE_8 -> 8
        android.view.KeyEvent.KEYCODE_9 -> 9
        else -> null
    }

    fun isDirectCommit(charText: String): Boolean {
        if (charText == " ") return true
        val c = charText.firstOrNull() ?: return false
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '.' || c == '-' || c == '@' || c == '_' ||
                (c in "、。・？！")
    }

    fun toggleChar(input: String): String = TOGGLE_MAP[input] ?: input

    // ---------------------------------------------------------------------------------------------
    // Region: Layout Specifications (1st Stage, 2nd Stage, Numeric)
    // ---------------------------------------------------------------------------------------------

    fun getLayoutSpec(hasInput: Boolean = true, isHiragana: Boolean = true): List<List<KeySpec>> {
        val chunkOrEmoji = if (hasInput) {
            KeySpec("区切", KeyType.CHUNK_FIX)
        } else {
            KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24)
        }
        val starKey = if (isHiragana) {
            KeySpec(text = "", type = KeyType.NIKO_TOGGLE, subText = "゛゜", subText2 = "大⇔小")
        } else {
            KeySpec("*", KeyType.NIKO_INPUT_STAR)
        }
        val poundKey = if (isHiragana) {
            KeySpec("@!?", KeyType.NIKO_SYMBOL_PALETTE)
        } else {
            KeySpec("#", KeyType.NIKO_INPUT_POUND)
        }
        return listOf(
            listOf(
                KeySpec("Esc", KeyType.ESC),
                KeySpec("あ", KeyType.NIKO_KEY_1, subText = "1", subText2 = "@"),
                KeySpec("か", KeyType.NIKO_KEY_2, subText = "2", subText2 = "ABC"),
                KeySpec("さ", KeyType.NIKO_KEY_3, subText = "3", subText2 = "DEF"),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("た", KeyType.NIKO_KEY_4, subText = "4", subText2 = "GHI"),
                KeySpec("な", KeyType.NIKO_KEY_5, subText = "5", subText2 = "JKL"),
                KeySpec("は", KeyType.NIKO_KEY_6, subText = "6", subText2 = "MNO"),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                chunkOrEmoji,
                KeySpec("ま", KeyType.NIKO_KEY_7, subText = "7", subText2 = "PQRS"),
                KeySpec("や", KeyType.NIKO_KEY_8, subText = "8", subText2 = "TUV"),
                KeySpec("ら", KeyType.NIKO_KEY_9, subText = "9", subText2 = "WXYZ"),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                starKey,
                KeySpec("わ", KeyType.NIKO_KEY_0, subText = "0", subText2 = "をん"),
                poundKey,
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }

    fun getSecondStageLayoutSpec(rowKey: Int, isHiragana: Boolean = true): List<List<KeySpec>> {
        val guideChars = getRowGuideChars(rowKey) ?: listOf("", "", "", "", "", "", "", "", "", "")
        val starKey = if (isHiragana) {
            KeySpec(text = "", type = KeyType.NIKO_TOGGLE, subText = "゛゜", subText2 = "大⇔小")
        } else {
            KeySpec("*", KeyType.NIKO_INPUT_STAR)
        }
        val poundKey = if (isHiragana) {
            KeySpec("@!?", KeyType.NIKO_SYMBOL_PALETTE)
        } else {
            KeySpec("#", KeyType.NIKO_INPUT_POUND)
        }
        return listOf(
            listOf(
                KeySpec("戻る", KeyType.NIKO_CANCEL),
                KeySpec(guideChars.getOrElse(0) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(1) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(2) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("⌫", KeyType.NIKO_BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec(guideChars.getOrElse(3) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(4) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(5) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                KeySpec("区切", KeyType.CHUNK_FIX),
                KeySpec(guideChars.getOrElse(6) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(7) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(8) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                starKey,
                KeySpec(guideChars.getOrElse(9) { "" }, KeyType.NIKO_GUIDE_CHAR),
                poundKey,
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }

    fun getNumericLayoutSpec(hasInput: Boolean = true): List<List<KeySpec>> {
        val chunkOrEmoji = if (hasInput) {
            KeySpec("区切", KeyType.CHUNK_FIX)
        } else {
            KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24)
        }
        return listOf(
            listOf(
                KeySpec("Esc", KeyType.ESC),
                KeySpec("1", KeyType.NIKO_KEY_1),
                KeySpec("2", KeyType.NIKO_KEY_2),
                KeySpec("3", KeyType.NIKO_KEY_3),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("4", KeyType.NIKO_KEY_4),
                KeySpec("5", KeyType.NIKO_KEY_5),
                KeySpec("6", KeyType.NIKO_KEY_6),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                chunkOrEmoji,
                KeySpec("7", KeyType.NIKO_KEY_7),
                KeySpec("8", KeyType.NIKO_KEY_8),
                KeySpec("9", KeyType.NIKO_KEY_9),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                KeySpec("*", KeyType.NIKO_INPUT_STAR),
                KeySpec("0", KeyType.NIKO_KEY_0),
                KeySpec("#", KeyType.NIKO_INPUT_POUND),
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }
}
