package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.StateSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.hypot

enum class QwertyMode {
    NORMAL,
    SHIFT,
    SYMBOL
}

/**
 * キーボードボタンの種別を定義する列挙型 (文字キーかそれ以外の機能キーか)
 */
enum class KeyType {
    CHARACTER,     // 通常の文字入力
    BACKSPACE,     // バックスペース
    SPACE,         // スペース
    ENTER,         // Enter
    SHIFT,         // シフト面切り替え
    SYMBOL,        // 記号面へ移動
    NORMAL,        // NORMAL面へ移動
    LANG,          // 日英トグル (A / あ)
    EMOJI,         // 絵文字パレット
    DPAD_LEFT,     // カーソル左
    DPAD_RIGHT,    // カーソル右
    DPAD_UP,       // カーソル上
    DPAD_DOWN      // カーソル下
}

/**
 * テーマごとのカラーパレットを保持するデータクラス
 */
data class KeyboardPalette(
    val keyBg: Int,
    val actionKeyBg: Int,
    val keyText: Int,
    val keyPressedBg: Int,
    val actionKeyPressedBg: Int,
    val activeKeyBg: Int,
    val activeKeyPressedBg: Int,
    val activeKeyText: Int,
    val disabledBg: Int
)

/**
 * キーボードの色（カラーコード）を一元管理するオブジェクト
 */
object KeyboardColors {
    val Dark = KeyboardPalette(
        keyBg = Color.parseColor("#3C4043"),
        actionKeyBg = Color.parseColor("#5F6368"),
        keyText = Color.WHITE,
        keyPressedBg = Color.parseColor("#5F6368"),
        actionKeyPressedBg = Color.parseColor("#70757A"),
        activeKeyBg = Color.parseColor("#8AB4F8"),
        activeKeyPressedBg = Color.parseColor("#669DF6"),
        activeKeyText = Color.parseColor("#202124"),
        disabledBg = Color.parseColor("#202124")
    )

    val Light = KeyboardPalette(
        keyBg = Color.WHITE,
        actionKeyBg = Color.parseColor("#DADCE0"),
        keyText = Color.parseColor("#202124"),
        keyPressedBg = Color.parseColor("#E8EAED"),
        actionKeyPressedBg = Color.parseColor("#BEC1C5"),
        activeKeyBg = Color.parseColor("#1A73E8"),
        activeKeyPressedBg = Color.parseColor("#174EA6"),
        activeKeyText = Color.WHITE,
        disabledBg = Color.parseColor("#F1F3F4")
    )

    fun get(isDark: Boolean): KeyboardPalette = if (isDark) Dark else Light
}

/**
 * 1つのキーの仕様を表すデータクラス (テーブル駆動レイアウト用)
 */
data class KeySpec(
    val normalText: String = "",
    val shiftText: String = normalText.uppercase(),
    val symbolText: String = normalText,
    val normalType: KeyType = KeyType.CHARACTER,
    val shiftType: KeyType = normalType,
    val symbolType: KeyType = normalType,
    val weight: Float = 1f,
    val textSize: Float = 16f,
    val repeatable: Boolean = false
) {
    fun getType(mode: QwertyMode): KeyType = when (mode) {
        QwertyMode.NORMAL -> normalType
        QwertyMode.SHIFT -> shiftType
        QwertyMode.SYMBOL -> symbolType
    }

    fun getText(mode: QwertyMode): String = when (mode) {
        QwertyMode.NORMAL -> normalText
        QwertyMode.SHIFT -> shiftText
        QwertyMode.SYMBOL -> symbolText
    }
}

/**
 * フリックキーボードの1キーの仕様を表すデータクラス
 */
data class FlickKeySpec(
    val label: String,
    val center: String,
    val left: String,
    val up: String,
    val right: String,
    val down: String,
    val isAction: Boolean = false
) {
    val chars: List<String>
        get() = listOf(center, left, up, right, down)
}

/**
 * inset と selector (StateListDrawable) を組み合わせたボタン背景 Drawable を生成
 */
fun createKeyButtonDrawable(
    context: Context,
    normalColor: Int,
    pressedColor: Int,
    disabledColor: Int,
    cornerRadiusDp: Float = 4f,
    insetDp: Float = 2f
): Drawable {
    val density = context.resources.displayMetrics.density
    val radiusPx = cornerRadiusDp * density
    val insetPx = (insetDp * density).toInt()

    val stateListDrawable = StateListDrawable().apply {
        // 無効時 (state_enabled="false")
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(disabledColor)
                cornerRadius = radiusPx
            }
        )
        // 押下時 (state_pressed="true")
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(pressedColor)
                cornerRadius = radiusPx
            }
        )
        // 選択時 (state_selected="true")
        addState(
            intArrayOf(android.R.attr.state_selected),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(pressedColor)
                cornerRadius = radiusPx
            }
        )
        // 通常時
        addState(
            StateSet.WILD_CARD,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(normalColor)
                cornerRadius = radiusPx
            }
        )
    }

    return if (insetPx > 0) {
        InsetDrawable(stateListDrawable, insetPx, insetPx, insetPx, insetPx)
    } else {
        stateListDrawable
    }
}

/**
 * キーボード用の拡張 Button クラス (ラベル、機能、ウェイト等をカプセル化)
 */
class KeyButton(
    context: Context,
    val spec: KeySpec,
    keyHeight: Int,
    val keyBgColor: Int,
    val actionKeyBgColor: Int,
    val keyTextColor: Int,
    onFeedback: () -> Unit
) : AppCompatButton(ContextThemeWrapper(context, R.style.Theme_Karukan)) {

    var currentType: KeyType = spec.normalType
        private set

    // currentType が CHARACTER 以外の場合は機能キー（アクションキー）とみなす
    val isActionKey: Boolean
        get() = currentType != KeyType.CHARACTER

    init {
        isAllCaps = false
        ViewCompat.setBackgroundTintList(this, null)
        text = spec.normalText
        updateTextSize(spec.normalText, spec.textSize)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, keyHeight, spec.weight).apply {
            setMargins(0, 0, 0, 0)
        }
        resetStyle(isDark = false)
        setOnClickListener {
            onFeedback()
        }
    }

    fun updateMode(mode: QwertyMode, isDark: Boolean) {
        currentType = spec.getType(mode)
        val newText = spec.getText(mode)
        text = newText
        updateTextSize(newText, spec.textSize)

        val palette = KeyboardColors.get(isDark)

        // Shift キーや Symbol 切替キーのアクティブ（モード有効時）ハイライト表現
        val isActiveState = when (currentType) {
            KeyType.SHIFT -> mode == QwertyMode.SHIFT
            KeyType.SYMBOL -> mode == QwertyMode.SYMBOL
            else -> false
        }

        if (isActiveState) {
            updateStyle(
                normalColor = palette.activeKeyBg,
                pressedColor = palette.activeKeyPressedBg,
                disabledColor = palette.disabledBg,
                textColor = palette.activeKeyText
            )
        } else {
            resetStyle(isDark)
        }
    }

    private fun updateTextSize(t: String, defaultSize: Float) {
        val isSingleAscii = t.length == 1 && t[0].code in 0..127
        textSize = if (isSingleAscii) maxOf(defaultSize + 3f, 19f) else defaultSize
    }

    fun updateStyle(
        normalColor: Int,
        pressedColor: Int,
        disabledColor: Int,
        textColor: Int
    ) {
        ViewCompat.setBackgroundTintList(this, null)
        background = createKeyButtonDrawable(
            context = context,
            normalColor = normalColor,
            pressedColor = pressedColor,
            disabledColor = disabledColor
        )
        setTextColor(textColor)
    }

    private fun resetStyle(isDark: Boolean) {
        val palette = KeyboardColors.get(isDark)
        val normalBg = if (isActionKey) actionKeyBgColor else keyBgColor
        val pressedBg = if (isActionKey) palette.actionKeyPressedBg else palette.keyPressedBg

        updateStyle(
            normalColor = normalBg,
            pressedColor = pressedBg,
            disabledColor = palette.disabledBg,
            textColor = keyTextColor
        )
    }
}

/**
 * QWERTY (5段構成, Shift/Symbol面対応) および 12キー フリックキーボードのレイアウト構築を行う Builder クラス
 */
class KeyboardLayoutBuilder(
    private val context: Context,
    private val isDarkThemeSupplier: () -> Boolean,
    private val flickSensitivitySupplier: () -> String,
    private val keyButtonHeightSupplier: () -> Int,
    private val isEnglishModeSupplier: () -> Boolean = { false },
    private val listener: KeyboardEventListener
) {
    private var currentQwertyMode: QwertyMode = QwertyMode.NORMAL

    private val isDark: Boolean
        get() = isDarkThemeSupplier()

    private val keyBgColor: Int
        get() = KeyboardColors.get(isDark).keyBg

    private val actionKeyBgColor: Int
        get() = KeyboardColors.get(isDark).actionKeyBg

    private val keyTextColor: Int
        get() = KeyboardColors.get(isDark).keyText

    private fun createKeyButton(spec: KeySpec): KeyButton {
        return KeyButton(
            context = context,
            spec = spec,
            keyHeight = keyButtonHeightSupplier(),
            keyBgColor = keyBgColor,
            actionKeyBgColor = actionKeyBgColor,
            keyTextColor = keyTextColor,
            onFeedback = { listener.onKeyFeedback() }
        )
    }

    private fun setupRepeatableTouchListener(btn: KeyButton) {
        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null
        var isHeld = false

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

    interface KeyboardEventListener {
        fun onKeyFeedback()
        fun onKeyPress(key: String)
        fun onFlickInput(char: String)
        fun onBackspace()
        fun onSpaceOrConvert()
        fun onCommit()
        fun onToggleEmojiPalette()
        fun onToggleLanguageMode()
        fun onDpadLeft()
        fun onDpadRight()
        fun onDpadUp()
        fun onDpadDown()
    }

    private fun getQwertyLayoutSpec(): List<List<KeySpec>> {
        val isEn = isEnglishModeSupplier()
        return listOf(
            // 1段目 (Row 0): 数字行
            listOf(
                KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
                KeySpec("6"), KeySpec("7"), KeySpec("8"), KeySpec("9"), KeySpec("0")
            ),
            // 2段目 (Row 1): q .. p
            listOf(
                KeySpec("q", shiftText = "Q", symbolText = "!"),
                KeySpec("w", shiftText = "W", symbolText = "@"),
                KeySpec("e", shiftText = "E", symbolText = "#"),
                KeySpec("r", shiftText = "R", symbolText = "$"),
                KeySpec("t", shiftText = "T", symbolText = "%"),
                KeySpec("y", shiftText = "Y", symbolText = "^"),
                KeySpec("u", shiftText = "U", symbolText = "&"),
                KeySpec("i", shiftText = "I", symbolText = "*"),
                KeySpec("o", shiftText = "O", symbolText = "("),
                KeySpec("p", shiftText = "P", symbolText = ")")
            ),
            // 3段目 (Row 2): a .. -
            listOf(
                KeySpec("a", shiftText = "A", symbolText = "+"),
                KeySpec("s", shiftText = "S", symbolText = "="),
                KeySpec("d", shiftText = "D", symbolText = "-"),
                KeySpec("f", shiftText = "F", symbolText = "/"),
                KeySpec("g", shiftText = "G", symbolText = "\\"),
                KeySpec("h", shiftText = "H", symbolText = ":"),
                KeySpec("j", shiftText = "J", symbolText = ";"),
                KeySpec("k", shiftText = "K", symbolText = "\""),
                KeySpec("l", shiftText = "L", symbolText = "'"),
                KeySpec("-", shiftText = "_", symbolText = "`")
            ),
            // 4段目 (Row 3): Shift, z..m, , , BS
            listOf(
                KeySpec(
                    normalText = "⇧",
                    shiftText = "⇧",
                    symbolText = "😊",
                    normalType = KeyType.SHIFT,
                    shiftType = KeyType.SHIFT,
                    symbolType = KeyType.EMOJI,
                    weight = 1.5f
                ),
                KeySpec("z", shiftText = "Z", symbolText = "~"),
                KeySpec("x", shiftText = "X", symbolText = "<"),
                KeySpec("c", shiftText = "C", symbolText = ">"),
                KeySpec("v", shiftText = "V", symbolText = "{"),
                KeySpec("b", shiftText = "B", symbolText = "}"),
                KeySpec("n", shiftText = "N", symbolText = "["),
                KeySpec("m", shiftText = "M", symbolText = "]"),
                KeySpec("⌫", normalType = KeyType.BACKSPACE, weight = 1.5f, repeatable = true)
            ),
            // 5段目 (Row 4): アクション行
            listOf(
                KeySpec(
                    normalText = "@!?",
                    shiftText = "@!?",
                    symbolText = "ABC",
                    normalType = KeyType.SYMBOL,
                    shiftType = KeyType.SYMBOL,
                    symbolType = KeyType.NORMAL,
                    weight = 1.5f
                ),
                KeySpec(if (isEn) "あ" else "A", normalType = KeyType.LANG),
                KeySpec(",", shiftText = "[", symbolText = "/"),
                KeySpec("⌴", normalType = KeyType.SPACE, weight = 2f),
                KeySpec(".", shiftText = "]", symbolText = "?"),
                KeySpec("←", normalType = KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("→", normalType = KeyType.DPAD_RIGHT, repeatable = true),
                KeySpec("⏎", normalType = KeyType.ENTER, weight = 1.5f)
            )
        )
    }

    fun createQwertyKeyboardContainer(): LinearLayout {
        currentQwertyMode = QwertyMode.NORMAL
        val keyboardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val allButtons = mutableListOf<KeyButton>()
        val layoutSpec = getQwertyLayoutSpec()

        for (rowSpec in layoutSpec) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (keySpec in rowSpec) {
                val btn = createKeyButton(keySpec)
                rowLayout.addView(btn)
                allButtons.add(btn)
            }
            keyboardContainer.addView(rowLayout)
        }

        setupGlobalListeners(allButtons)

        return keyboardContainer
    }

    private fun setupGlobalListeners(allButtons: List<KeyButton>) {
        fun updateAllModes() {
            for (btn in allButtons) {
                btn.updateMode(currentQwertyMode, isDark)
            }
        }

        for (btn in allButtons) {
            btn.setOnClickListener {
                listener.onKeyFeedback()
                when (btn.currentType) {
                    KeyType.CHARACTER -> {
                        listener.onKeyPress(btn.text.toString())
                        if (currentQwertyMode == QwertyMode.SHIFT) {
                            currentQwertyMode = QwertyMode.NORMAL
                            updateAllModes()
                        }
                    }

                    KeyType.BACKSPACE -> listener.onBackspace()
                    KeyType.SPACE -> listener.onSpaceOrConvert()
                    KeyType.ENTER -> listener.onCommit()

                    KeyType.SHIFT -> {
                        currentQwertyMode = when (currentQwertyMode) {
                            QwertyMode.NORMAL -> QwertyMode.SHIFT
                            QwertyMode.SHIFT -> QwertyMode.NORMAL
                            QwertyMode.SYMBOL -> QwertyMode.NORMAL
                        }
                        updateAllModes()
                    }

                    KeyType.SYMBOL -> {
                        currentQwertyMode = QwertyMode.SYMBOL
                        updateAllModes()
                    }

                    KeyType.NORMAL -> {
                        currentQwertyMode = QwertyMode.NORMAL
                        updateAllModes()
                    }

                    KeyType.LANG -> listener.onToggleLanguageMode()
                    KeyType.EMOJI -> listener.onToggleEmojiPalette()
                    KeyType.DPAD_LEFT -> listener.onDpadLeft()
                    KeyType.DPAD_RIGHT -> listener.onDpadRight()
                    KeyType.DPAD_UP -> listener.onDpadUp()
                    KeyType.DPAD_DOWN -> listener.onDpadDown()
                }
            }

            if (btn.spec.repeatable) {
                setupRepeatableTouchListener(btn)
            }
        }

        updateAllModes()
    }

    private companion object {
        val FLICK_KEYBOARD_SPEC: List<FlickKeySpec> = listOf(
            FlickKeySpec("あ", "あ", "い", "う", "え", "お"),
            FlickKeySpec("か", "か", "き", "く", "け", "こ"),
            FlickKeySpec("さ", "さ", "し", "す", "せ", "そ"),
            FlickKeySpec("た", "た", "ち", "つ", "て", "と"),
            FlickKeySpec("な", "な", "に", "ぬ", "ね", "の"),
            FlickKeySpec("は", "は", "ひ", "ふ", "へ", "ほ"),
            FlickKeySpec("ま", "ま", "み", "む", "め", "も"),
            FlickKeySpec("や", "や", "ゃ", "ゆ", "ゅ", "よ"),
            FlickKeySpec("ら", "ら", "り", "る", "れ", "ろ"),
            FlickKeySpec("わ", "わ", "を", "ん", "ー", "〜"),
            FlickKeySpec("゛゜", "゛", "゜", "小", "", "", isAction = true),
            FlickKeySpec("記号", "、", "。", "？", "！", "…", isAction = true)
        )
    }

    fun createFlickKeyboardContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val palette = KeyboardColors.get(isDark)

        for (r in 0..3) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            for (c in 0..2) {
                val idx = r * 3 + c
                val flickSpec = FLICK_KEYBOARD_SPEC[idx]

                val normalBg = if (flickSpec.isAction) palette.actionKeyBg else palette.keyBg
                val pressedBg = if (flickSpec.isAction) palette.actionKeyPressedBg else palette.keyPressedBg

                val btn = Button(context).apply {
                    isAllCaps = false
                    text = flickSpec.label
                    val isSingleAscii = flickSpec.label.length == 1 && flickSpec.label[0].code in 0..127
                    textSize = if (isSingleAscii) maxOf(18f + 3f, 19f) else 18f
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        keyButtonHeightSupplier(),
                        1f
                    ).apply {
                        setMargins(0, 0, 0, 0)
                    }

                    background = createKeyButtonDrawable(
                        context = context,
                        normalColor = normalBg,
                        pressedColor = pressedBg,
                        disabledColor = palette.disabledBg
                    )
                    setTextColor(palette.keyText)
                    setupFlickTouchListener(this, flickSpec.chars)
                }
                rowLayout.addView(btn)
            }
            container.addView(rowLayout)
        }

        val actionRowSpec = listOf(
            KeySpec("かな", normalType = KeyType.SYMBOL, weight = 1.2f),
            KeySpec("⌴", normalType = KeyType.SPACE, weight = 2.5f)
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (spec in actionRowSpec) {
            val btn = createKeyButton(spec)
            actionRow.addView(btn)
        }

        container.addView(actionRow)
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
                    listener.onKeyFeedback()
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
                        chars[0]
                    } else if (abs(dx) > abs(dy)) {
                        if (dx < 0) chars[1] else chars[3]
                    } else {
                        if (dy < 0) chars[2] else chars[4]
                    }

                    if (selectedChar.isNotEmpty()) {
                        listener.onFlickInput(selectedChar)
                    }
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun getFlickThresholdPx(): Float {
        val density = context.resources.displayMetrics.density
        return when (flickSensitivitySupplier()) {
            "sensitive" -> 30f * density
            "slow" -> 70f * density
            else -> 50f * density
        }
    }
}
