package io.github.kachaya.karukan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * QWERTY および 12キー フリックキーボードのレイアウト構築を行う Builder クラス
 */
class KeyboardLayoutBuilder(
    private val context: Context,
    private val isDarkThemeSupplier: () -> Boolean,
    private val isShowCursorKeysEnabledSupplier: () -> Boolean,
    private val isShowEmojiButtonEnabledSupplier: () -> Boolean,
    private val flickSensitivitySupplier: () -> String,
    private val keyButtonHeightSupplier: () -> Int,
    private val listener: KeyboardEventListener
) {
    private val isDark: Boolean
        get() = isDarkThemeSupplier()

    private val keyBgColor: Int
        get() = if (isDark) Color.parseColor("#3C4043") else Color.WHITE

    private val actionKeyBgColor: Int
        get() = if (isDark) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0")

    private val keyTextColor: Int
        get() = if (isDark) Color.WHITE else Color.parseColor("#202124")

    private fun styleButton(btn: Button, isActionKey: Boolean = false) {
        val bgColor = if (isActionKey) actionKeyBgColor else keyBgColor
        btn.backgroundTintList = ColorStateList.valueOf(bgColor)
        btn.setTextColor(keyTextColor)
    }
    interface KeyboardEventListener {
        fun onKeyFeedback()
        fun onKeyPress(key: String)
        fun onFlickInput(char: String)
        fun onBackspace()
        fun onSpaceOrConvert()
        fun onCommit()
        fun onClear()
        fun onToggleEmojiPalette()
        fun onSendKeyEvent(keyCode: Int)
    }

    fun createQwertyKeyboardContainer(): LinearLayout {
        val keyboardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val row1Keys = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2Keys = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-")
        val row3Keys = listOf("z", "x", "c", "v", "b", "n", "m", ",", ".")

        keyboardContainer.addView(createKeyRow(row1Keys))
        keyboardContainer.addView(createKeyRow(row2Keys))
        keyboardContainer.addView(createRow3WithBackspace(row3Keys))
        keyboardContainer.addView(createActionRow())

        return keyboardContainer
    }

    fun createFlickKeyboardContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }

        val flickMap = listOf(
            listOf("あ", "あ", "い", "う", "え", "お"),
            listOf("か", "か", "き", "く", "け", "こ"),
            listOf("さ", "さ", "し", "す", "せ", "そ"),
            listOf("た", "た", "ち", "つ", "て", "と"),
            listOf("な", "な", "に", "ぬ", "ね", "の"),
            listOf("は", "は", "ひ", "ふ", "へ", "ほ"),
            listOf("ま", "ま", "み", "む", "め", "も"),
            listOf("や", "や", "ゃ", "ゆ", "ゅ", "よ"),
            listOf("ら", "ら", "り", "る", "れ", "ろ"),
            listOf("わ", "わ", "を", "ん", "ー", "〜"),
            listOf("゛゜", "゛", "゜", "小", "", ""),
            listOf("記号", "、", "。", "？", "！", "…")
        )

        for (r in 0..3) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            for (c in 0..2) {
                val idx = r * 3 + c
                val flickItem = flickMap[idx]
                val label = flickItem[0]
                val chars = flickItem.subList(1, flickItem.size)

                val btn = Button(context).apply {
                    text = label
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        keyButtonHeightSupplier(),
                        1f
                    ).apply {
                        setMargins(4, 4, 4, 4)
                    }

                    styleButton(this, isActionKey = (label == "゛゜" || label == "記号"))
                    setupFlickTouchListener(this, chars)
                }
                rowLayout.addView(btn)
            }
            container.addView(rowLayout)
        }

        container.addView(createActionRow())
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

    private fun createKeyRow(keys: List<String>): LinearLayout {
        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnHeight = keyButtonHeightSupplier()

        for (key in keys) {
            val btn = Button(context).apply {
                text = key
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                styleButton(this)
                setOnClickListener {
                    listener.onKeyFeedback()
                    listener.onKeyPress(key)
                }
            }
            rowLayout.addView(btn)
        }
        return rowLayout
    }

    private fun createRow3WithBackspace(keys: List<String>): LinearLayout {
        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnHeight = keyButtonHeightSupplier()

        for (key in keys) {
            val btn = Button(context).apply {
                text = key
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    btnHeight,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                styleButton(this)
                setOnClickListener {
                    listener.onKeyFeedback()
                    listener.onKeyPress(key)
                }
            }
            rowLayout.addView(btn)
        }

        val btnBs = Button(context).apply {
            text = "⌫"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0,
                btnHeight,
                1.5f
            ).apply {
                setMargins(2, 2, 2, 2)
            }
            styleButton(this, isActionKey = true)
            setOnClickListener {
                listener.onKeyFeedback()
                listener.onBackspace()
            }
        }
        rowLayout.addView(btnBs)

        return rowLayout
    }

    private fun createActionRow(): LinearLayout {
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnHeight = keyButtonHeightSupplier()

        if (isShowCursorKeysEnabledSupplier()) {
            val btnLeft = Button(context).apply {
                text = "←"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                styleButton(this, isActionKey = true)
                setOnClickListener {
                    listener.onKeyFeedback()
                    listener.onSendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                }
            }
            val btnRight = Button(context).apply {
                text = "→"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                styleButton(this, isActionKey = true)
                setOnClickListener {
                    listener.onKeyFeedback()
                    listener.onSendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            actionRow.addView(btnLeft)
            actionRow.addView(btnRight)
        }

        if (isShowEmojiButtonEnabledSupplier()) {
            val btnEmoji = Button(context).apply {
                text = "😊"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, btnHeight, 0.8f).apply { setMargins(2, 2, 2, 2) }
                styleButton(this, isActionKey = true)
                setOnClickListener {
                    listener.onKeyFeedback()
                    listener.onToggleEmojiPalette()
                }
            }
            actionRow.addView(btnEmoji)
        }

        val btnSpace = Button(context).apply {
            text = "スペース / 変換"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                btnHeight,
                1.8f
            ).apply {
                setMargins(2, 2, 2, 2)
            }
            styleButton(this)
            setOnClickListener {
                listener.onKeyFeedback()
                listener.onSpaceOrConvert()
            }
        }

        val btnCommit = Button(context).apply {
            text = "確定"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                btnHeight,
                1f
            ).apply {
                setMargins(2, 2, 2, 2)
            }
            styleButton(this, isActionKey = true)
            setOnClickListener {
                listener.onKeyFeedback()
                listener.onCommit()
            }
        }

        val btnClear = Button(context).apply {
            text = "クリア"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                btnHeight,
                1f
            ).apply {
                setMargins(2, 2, 2, 2)
            }
            styleButton(this, isActionKey = true)
            setOnClickListener {
                listener.onKeyFeedback()
                listener.onClear()
            }
        }

        actionRow.addView(btnSpace)
        actionRow.addView(btnCommit)
        actionRow.addView(btnClear)

        return actionRow
    }
}