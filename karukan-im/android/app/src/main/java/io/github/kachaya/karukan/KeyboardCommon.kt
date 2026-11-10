package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.StateSet
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat

// -------------------------------------------------------------------------------------------------
// Region: Enums & Types
// ---------------------------------------------------------------------------------------------

enum class QwertyMode {
    NORMAL,
    SHIFT,
    SYMBOL,
    CTRL
}

/**
 * キーボードボタンの種別を定義する列挙型 (文字キーかそれ以外の機能キーか)
 */
enum class KeyType {
    CHARACTER,     // 通常の文字入力
    BACKSPACE,     // バックスペース (カーソル左削除)
    DELETE,        // デリート (カーソル右削除)
    SPACE,         // スペース
    ENTER,         // Enter
    SHIFT,         // シフト面切り替え
    SYMBOL,        // 記号面へ移動
    NORMAL,        // NORMAL面へ移動
    LANG,          // 日英トグル (A / あ)
    NIKO_LANG,     // ニコ用3段階トグル (あa1)
    EMOJI,         // 絵文字パレット
    DPAD_LEFT,     // カーソル左
    DPAD_RIGHT,    // カーソル右
    DPAD_UP,       // カーソル上
    DPAD_DOWN,     // カーソル下
    CTRL,          // Ctrlキー
    ESC,           // Escapeキー
    TAB,           // Tabキー
    KATAKANA,      // カタカナキー
    GAP,           // 行の隙間・余白スペーサー
    NIKO_KEY_0,    // ニコタッチ 0キー (0わ)
    NIKO_KEY_1,    // ニコタッチ 1キー
    NIKO_KEY_2,    // ニコタッチ 2キー
    NIKO_KEY_3,    // ニコタッチ 3キー
    NIKO_KEY_4,    // ニコタッチ 4キー
    NIKO_KEY_5,    // ニコタッチ 5キー
    NIKO_KEY_6,    // ニコタッチ 6キー
    NIKO_KEY_7,    // ニコタッチ 7キー
    NIKO_KEY_8,    // ニコタッチ 8キー
    NIKO_KEY_9,    // ニコタッチ 9キー
    KEY_STAR,      // * (STAR) キー (゛゜小)
    KEY_POUND,     // # (POUND) キー (確定)
    CHUNK_FIX,     // チャンク固定・文節区切りキー
    NIKO_GUIDE_CHAR, // ニコタッチ2打目ガイド文字キー
    NIKO_CANCEL,     // ニコタッチ1打目画面に戻るキー
    NIKO_BACKSPACE,  // ニコタッチ2打目バックスペースキー
    NIKO_TOGGLE,     // ニコタッチ濁音/小文字トグルキー (*)
    NIKO_INPUT_STAR, // ニコタッチ *入力キー
    NIKO_INPUT_POUND,// ニコタッチ #入力キー
    NIKO_SYMBOL_PALETTE; // ニコタッチ記号パレット呼び出しキー (#)

    val isNikoKey: Boolean
        get() = this in NIKO_KEY_0..NIKO_KEY_9

    val nikoKeyIndex: Int?
        get() = when (this) {
            NIKO_KEY_0 -> 0
            NIKO_KEY_1 -> 1
            NIKO_KEY_2 -> 2
            NIKO_KEY_3 -> 3
            NIKO_KEY_4 -> 4
            NIKO_KEY_5 -> 5
            NIKO_KEY_6 -> 6
            NIKO_KEY_7 -> 7
            NIKO_KEY_8 -> 8
            NIKO_KEY_9 -> 9
            else -> null
        }
}

enum class InputMode {
    HIRAGANA,      // あ (日本語かな)
    ALPHANUMERIC,  // a (英数)
    NUMERIC        // 1 (数字専用)
}

enum class KeyBackgroundType {
    NORMAL,
    ACTION,
    ACTIVE
}

// -------------------------------------------------------------------------------------------------
// Region: Color Palettes
// -------------------------------------------------------------------------------------------------

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
    private val baseDarkBg = "#202124".toColorInt()
    private val baseLightBg = "#F8F9FA".toColorInt()
    private val baseAccentBlue = "#1A73E8".toColorInt()

    val Dark = KeyboardPalette(
        keyBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.11f),
        actionKeyBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.25f),
        keyText = Color.WHITE,
        keyPressedBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.25f),
        actionKeyPressedBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.32f),
        activeKeyBg = ColorUtils.blendARGB(baseDarkBg, baseAccentBlue, 0.70f),
        activeKeyPressedBg = ColorUtils.blendARGB(baseDarkBg, baseAccentBlue, 0.85f),
        activeKeyText = "#202124".toColorInt(),
        disabledBg = baseDarkBg
    )

    val Light = KeyboardPalette(
        keyBg = Color.WHITE,
        actionKeyBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.12f),
        keyText = "#202124".toColorInt(),
        keyPressedBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.08f),
        actionKeyPressedBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.22f),
        activeKeyBg = baseAccentBlue,
        activeKeyPressedBg = ColorUtils.blendARGB(baseAccentBlue, Color.BLACK, 0.20f),
        activeKeyText = Color.WHITE,
        disabledBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.04f)
    )

    fun get(isDark: Boolean): KeyboardPalette = if (isDark) Dark else Light
}

// -------------------------------------------------------------------------------------------------
// Region: Key Specifications (KeySpec)
// -------------------------------------------------------------------------------------------------

/**
 * 1つのキーの仕様を表すデータクラス (テーブル駆動レイアウト用)
 */
data class KeySpec(
    val normalText: String = "",
    val shiftText: String = normalText.uppercase(),
    val symbolText: String = normalText,
    val ctrlText: String = normalText,
    val subText: String = "",
    val subText2: String = "",
    val normalType: KeyType = KeyType.CHARACTER,
    val shiftType: KeyType = normalType,
    val symbolType: KeyType = normalType,
    val ctrlType: KeyType = normalType,
    val weight: Float = 1f,
    val textSize: Float = 22f,
    val repeatable: Boolean = false,
    val drawableResId: Int = 0
) {
    // アイコン/機能キー用コンストラクタ
    constructor(
        text: String,
        type: KeyType,
        drawableResId: Int,
        weight: Float = 1f,
        textSize: Float = 22f,
        repeatable: Boolean = false
    ) : this(
        normalText = text,
        shiftText = text,
        symbolText = text,
        ctrlText = text,
        normalType = type,
        shiftType = type,
        symbolType = type,
        ctrlType = type,
        weight = weight,
        textSize = textSize,
        repeatable = repeatable,
        drawableResId = drawableResId
    )

    // 文字用コンストラクタ
    constructor(
        text: String,
        subText: String = "",
        subText2: String = "",
        shiftText: String = text.uppercase(),
        weight: Float = 1f,
        textSize: Float = 22f
    ) : this(
        normalText = text,
        shiftText = shiftText,
        symbolText = text,
        ctrlText = text,
        subText = subText,
        subText2 = subText2,
        normalType = KeyType.CHARACTER,
        shiftType = KeyType.CHARACTER,
        symbolType = KeyType.CHARACTER,
        ctrlType = KeyType.CHARACTER,
        weight = weight,
        textSize = textSize
    )

    // 機能・アクション・ニコキー用コンストラクタ
    constructor(
        text: String,
        type: KeyType,
        subText: String = "",
        subText2: String = "",
        weight: Float = 1f,
        textSize: Float = 22f,
        repeatable: Boolean = false
    ) : this(
        normalText = text,
        shiftText = text,
        symbolText = text,
        ctrlText = text,
        subText = subText,
        subText2 = subText2,
        normalType = type,
        shiftType = type,
        symbolType = type,
        ctrlType = type,
        weight = weight,
        textSize = textSize,
        repeatable = repeatable
    )

    companion object {
        fun gap(weight: Float = 1f): KeySpec = KeySpec(
            normalType = KeyType.GAP,
            shiftType = KeyType.GAP,
            symbolType = KeyType.GAP,
            ctrlType = KeyType.GAP,
            weight = weight
        )
    }

    fun getType(mode: QwertyMode): KeyType = when (mode) {
        QwertyMode.NORMAL -> normalType
        QwertyMode.SHIFT -> shiftType
        QwertyMode.SYMBOL -> symbolType
        QwertyMode.CTRL -> ctrlType
    }

    fun getText(mode: QwertyMode): String = when (mode) {
        QwertyMode.NORMAL -> normalText
        QwertyMode.SHIFT -> {
            if (shiftType != KeyType.CHARACTER || normalType != KeyType.CHARACTER) {
                if (shiftText == normalText.uppercase()) normalText else shiftText
            } else {
                shiftText
            }
        }

        QwertyMode.SYMBOL -> symbolText
        QwertyMode.CTRL -> ctrlText
    }
}

// -------------------------------------------------------------------------------------------------
// Region: Key Background & Drawable Factory
// -------------------------------------------------------------------------------------------------

fun createKeyDrawableByType(
    context: Context,
    isDark: Boolean,
    type: KeyBackgroundType,
    cornerRadiusDp: Float = 4f,
    insetDp: Float = 2f
): Drawable {
    val palette = KeyboardColors.get(isDark)
    val (normalColor, pressedColor) = when (type) {
        KeyBackgroundType.NORMAL -> palette.keyBg to palette.keyPressedBg
        KeyBackgroundType.ACTION -> palette.actionKeyBg to palette.actionKeyPressedBg
        KeyBackgroundType.ACTIVE -> palette.activeKeyBg to palette.activeKeyPressedBg
    }

    return createKeyButtonDrawable(
        context = context,
        normalColor = normalColor,
        pressedColor = pressedColor,
        disabledColor = palette.disabledBg,
        cornerRadiusDp = cornerRadiusDp,
        insetDp = insetDp
    )
}

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
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(disabledColor)
                cornerRadius = radiusPx
            }
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(pressedColor)
                cornerRadius = radiusPx
            }
        )
        addState(
            intArrayOf(android.R.attr.state_selected),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(pressedColor)
                cornerRadius = radiusPx
            }
        )
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

// -------------------------------------------------------------------------------------------------
// Region: Spannable Label Builders
// -------------------------------------------------------------------------------------------------

fun buildInputModeKeyText(inputMode: InputMode, isDark: Boolean): CharSequence {
    val mainColor = if (isDark) Color.WHITE else "#202124".toColorInt()
    val subColor = if (isDark) "#9AA0A6".toColorInt() else "#70757A".toColorInt()

    val hColor = if (inputMode == InputMode.HIRAGANA) mainColor else subColor
    val nColor = if (inputMode == InputMode.NUMERIC) mainColor else subColor

    return SpannableStringBuilder().apply {
        val hStart = length
        append("あ")
        setSpan(ForegroundColorSpan(hColor), hStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val nStart = length
        append("1")
        setSpan(ForegroundColorSpan(nColor), nStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

fun buildLangKeyText(isEnglishMode: Boolean, isDark: Boolean): CharSequence {
    val mainColor = if (isDark) Color.WHITE else "#202124".toColorInt()
    val subColor = if (isDark) "#9AA0A6".toColorInt() else "#70757A".toColorInt()

    val hColor = if (!isEnglishMode) mainColor else subColor
    val aColor = if (isEnglishMode) mainColor else subColor

    return SpannableStringBuilder().apply {
        val hStart = length
        append("あ")
        setSpan(ForegroundColorSpan(hColor), hStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val aStart = length
        append("a")
        setSpan(ForegroundColorSpan(aColor), aStart, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

// -------------------------------------------------------------------------------------------------
// Region: Custom KeyButton View
// -------------------------------------------------------------------------------------------------

class KeyButton(
    context: Context,
    val spec: KeySpec,
    keyHeight: Int,
    val keyTextColor: Int,
    val isDark: Boolean = false,
    onFeedback: () -> Unit
) : View(ContextThemeWrapper(context, R.style.Theme_Karukan)) {

    var currentType: KeyType = spec.normalType
        private set

    var currentInputMode: InputMode = InputMode.HIRAGANA
        private set

    var text: CharSequence = ""
        set(value) {
            field = value
            invalidate()
        }

    var currentTextColor: Int = keyTextColor
        set(value) {
            field = value
            invalidate()
        }

    val isActionKey: Boolean
        get() {
            if (currentType == KeyType.KEY_STAR && (currentInputMode == InputMode.NUMERIC || currentInputMode == InputMode.ALPHANUMERIC)) {
                return false
            }
            if (currentType == KeyType.KEY_POUND && (currentInputMode == InputMode.NUMERIC || currentInputMode == InputMode.ALPHANUMERIC)) {
                return false
            }
            return currentType != KeyType.CHARACTER && currentType != KeyType.NIKO_GUIDE_CHAR && !currentType.isNikoKey
        }

    private var lastBgType: KeyBackgroundType? = null
    private var lastIsDark: Boolean = isDark
    private var lastInputMode: InputMode = InputMode.HIRAGANA
    private var lastIsEnglishMode: Boolean = false

    private val mainPaint = TextPaint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val subPaint = TextPaint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private var isGuideMode: Boolean = false

    init {
        isClickable = true
        isFocusable = true
        ViewCompat.setBackgroundTintList(this, null)
        stateListAnimator = null
        ViewCompat.setElevation(this, 0f)
        elevation = 0f
        layoutParams = LinearLayout.LayoutParams(0, keyHeight, spec.weight).apply {
            setMargins(0, 0, 0, 0)
        }
        if (spec.normalType == KeyType.GAP) {
            text = ""
            background = null
            isClickable = false
            isFocusable = false
            isEnabled = false
        } else {
            if (spec.normalType == KeyType.LANG) {
                text = buildLangKeyText(lastIsEnglishMode, isDark = lastIsDark)
            } else if (spec.normalType == KeyType.NIKO_LANG) {
                text = buildInputModeKeyText(InputMode.HIRAGANA, isDark = lastIsDark)
            } else {
                setMainAndSubText(spec.normalText, spec.subText, spec.subText2)
            }
            setPadding(0, 0, 0, 0)
            resetStyle()
            setOnClickListener {
                onFeedback()
            }
        }
    }

    fun setGuideText(guideText: String) {
        text = guideText
        isGuideMode = true
        invalidate()
    }

    fun clearGuideText() {
        isGuideMode = false
        invalidate()
    }

    fun setMainAndSubText(mainText: String, subText: String = "", subText2: String = "") {
        text = mainText
        isGuideMode = false
    }

    override fun onDraw(canvas: Canvas) {
        if (spec.drawableResId != 0 && !isGuideMode) {
            drawDrawable(canvas)
            return
        }

        val subT = if (isGuideMode) "" else spec.subText
        val subT2 = if (isGuideMode) "" else spec.subText2

        if (subT.isNotEmpty() && subT2.isNotEmpty() && currentType.isNikoKey && currentInputMode == InputMode.HIRAGANA) {
            drawNikoKeyText(canvas)
        } else if (text.isEmpty() && subT.isNotEmpty() && subT2.isNotEmpty()) {
            drawSubTextsAsTwoLines(canvas)
        } else {
            drawStandardKeyText(canvas)
        }
    }

    private fun drawDrawable(canvas: Canvas) {
        val resId = spec.drawableResId
        if (resId == 0) return

        val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)?.mutate() ?: return
        androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, currentTextColor)

        val density = context.resources.displayMetrics.density
        val iconSize = (24f * density).toInt()
        val left = (width - iconSize) / 2
        val top = (height - iconSize) / 2
        drawable.setBounds(left, top, left + iconSize, top + iconSize)
        drawable.draw(canvas)
    }

    private fun drawSubTextsAsTwoLines(canvas: Canvas) {
        val subT = spec.subText
        val subT2 = spec.subText2
        if (subT.isEmpty() || subT2.isEmpty()) return

        val density = context.resources.displayMetrics.density
        mainPaint.textSize = 11f * density
        mainPaint.color = currentTextColor

        val combinedText = "$subT\n$subT2"
        val layout = StaticLayout.Builder.obtain(combinedText, 0, combinedText.length, mainPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        val y = (height - layout.height) / 2f
        canvas.save()
        canvas.translate(0f, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawStandardKeyText(canvas: Canvas) {
        val t = text
        if (t.isEmpty()) return

        val density = context.resources.displayMetrics.density
        val textStr = t.toString()
        mainPaint.textSize = (if (textStr.length >= 2) 16f else 22f) * density
        mainPaint.color = currentTextColor

        val layout = StaticLayout.Builder.obtain(t, 0, t.length, mainPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        val y = (height - layout.height) / 2f
        canvas.save()
        canvas.translate(0f, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawNikoKeyText(canvas: Canvas) {
        val mainT = text.toString()
        val subT = spec.subText
        val subT2 = spec.subText2

        val density = context.resources.displayMetrics.density
        mainPaint.textSize = 21f * density
        mainPaint.color = currentTextColor
        mainPaint.textAlign = Paint.Align.RIGHT

        subPaint.textSize = 10f * density
        subPaint.color = currentTextColor
        subPaint.textAlign = Paint.Align.LEFT

        val mainMetrics = mainPaint.fontMetrics
        val subMetrics = subPaint.fontMetrics

        val mainHeight = mainMetrics.descent - mainMetrics.ascent
        val subLineHeight = subMetrics.descent - subMetrics.ascent
        val subGap = 2f * density
        val totalSubHeight = subLineHeight * 2 + subGap

        val groupHeight = maxOf(mainHeight, totalSubHeight)
        val groupTop = (height - groupHeight) / 2f
        val commonBottom = groupTop + groupHeight

        val mainY = commonBottom - mainMetrics.descent
        val sub2Y = commonBottom - subMetrics.descent
        val sub1Y = sub2Y - subLineHeight - subGap

        val centerX = width / 2f
        val gap = 3f * density

        canvas.drawText(mainT, centerX - gap, mainY, mainPaint)

        val rightX = centerX + gap
        canvas.drawText(subT, rightX, sub1Y, subPaint)
        canvas.drawText(subT2, rightX, sub2Y, subPaint)
    }

    fun updateBackgroundType(type: KeyBackgroundType, isDark: Boolean) {
        if (lastBgType != type || lastIsDark != isDark) {
            ViewCompat.setBackgroundTintList(this, null)
            background = createKeyDrawableByType(context, isDark, type)
            lastBgType = type
            lastIsDark = isDark
        }
    }

    fun updateInputMode(inputMode: InputMode, isDark: Boolean = lastIsDark) {
        lastInputMode = inputMode
        currentInputMode = inputMode
        lastIsDark = isDark
        if (currentType == KeyType.NIKO_LANG || spec.normalType == KeyType.NIKO_LANG) {
            text = buildInputModeKeyText(inputMode, isDark)
        } else if (currentType == KeyType.LANG || spec.normalType == KeyType.LANG) {
            val isEng = inputMode != InputMode.HIRAGANA
            lastIsEnglishMode = isEng
            text = buildLangKeyText(isEng, isDark)
        } else if (currentType == KeyType.KEY_STAR || spec.normalType == KeyType.KEY_STAR) {
            invalidate()
        }
    }

    fun updateLanguageMode(isEnglishMode: Boolean, isDark: Boolean = lastIsDark) {
        lastIsEnglishMode = isEnglishMode
        lastIsDark = isDark
        if (currentType == KeyType.LANG || spec.normalType == KeyType.LANG) {
            text = buildLangKeyText(isEnglishMode, isDark)
        } else if (currentType == KeyType.NIKO_LANG || spec.normalType == KeyType.NIKO_LANG) {
            val mode = if (isEnglishMode) InputMode.ALPHANUMERIC else InputMode.HIRAGANA
            lastInputMode = mode
            currentInputMode = mode
            text = buildInputModeKeyText(mode, isDark)
        }
    }

    fun updateMode(mode: QwertyMode, isDark: Boolean) {
        lastIsDark = isDark
        currentType = spec.getType(mode)
        if (currentType == KeyType.GAP) {
            text = ""
            background = null
            isClickable = false
            isFocusable = false
            isEnabled = false
            lastBgType = null
            return
        }

        if (currentType == KeyType.NIKO_LANG) {
            text = buildInputModeKeyText(lastInputMode, isDark)
        } else if (currentType == KeyType.LANG) {
            text = buildLangKeyText(lastIsEnglishMode, isDark)
        } else {
            val newText = spec.getText(mode)
            if (text != newText) {
                text = newText
                setMainAndSubText(
                    newText,
                    if (mode == QwertyMode.NORMAL) spec.subText else "",
                    if (mode == QwertyMode.NORMAL) spec.subText2 else ""
                )
            }
        }

        val palette = KeyboardColors.get(isDark)

        val isActiveState = when (currentType) {
            KeyType.SHIFT -> mode == QwertyMode.SHIFT
            KeyType.SYMBOL -> mode == QwertyMode.SYMBOL
            KeyType.CTRL -> mode == QwertyMode.CTRL
            else -> false
        }

        val targetBgType = if (isActiveState) {
            KeyBackgroundType.ACTIVE
        } else if (isActionKey) {
            KeyBackgroundType.ACTION
        } else {
            KeyBackgroundType.NORMAL
        }

        val targetTextColor = if (isActiveState) palette.activeKeyText else palette.keyText

        if (lastBgType != targetBgType || lastIsDark != isDark) {
            ViewCompat.setBackgroundTintList(this, null)
            background = createKeyDrawableByType(context, isDark, targetBgType)
            lastBgType = targetBgType
            lastIsDark = isDark
        }

        if (currentType == KeyType.LANG || currentType == KeyType.NIKO_LANG) {
            currentTextColor = keyTextColor
        } else if (currentTextColor != targetTextColor) {
            currentTextColor = targetTextColor
        }
    }

    private fun resetStyle(isDark: Boolean = lastIsDark) {
        lastIsDark = isDark
        val targetBgType = if (isActionKey) KeyBackgroundType.ACTION else KeyBackgroundType.NORMAL
        ViewCompat.setBackgroundTintList(this, null)
        background = createKeyDrawableByType(context, isDark, targetBgType)
        if (currentType == KeyType.NIKO_LANG || spec.normalType == KeyType.NIKO_LANG) {
            text = buildInputModeKeyText(lastInputMode, isDark)
        } else if (currentType == KeyType.LANG || spec.normalType == KeyType.LANG) {
            text = buildLangKeyText(lastIsEnglishMode, isDark)
        } else {
            setMainAndSubText(spec.normalText, spec.subText, spec.subText2)
        }
        currentTextColor = keyTextColor
    }
}

// -------------------------------------------------------------------------------------------------
// Region: Event Listener Interface
// -------------------------------------------------------------------------------------------------

interface KeyboardEventListener {
    fun onKeyFeedback()
    fun onKeyPress(key: String, isShift: Boolean = false)
    fun onNikoTouchInput(char: String)
    fun onNikoTouchToggle()
    fun onBackspace()
    fun onDelete()
    fun onCtrlBackspace()
    fun onSpaceOrConvert()
    fun onCommit()
    fun onToggleEmojiPalette()
    fun onToggleSymbolPalette()
    fun onToggleLanguageMode()
    fun onNikoToggleLanguageMode()
    fun onDpadLeft()
    fun onDpadRight()
    fun onDpadUp()
    fun onDpadDown()
    fun onCtrlShortcut(key: String)
    fun onEsc()
    fun onTab()
    fun onKatakana()
    fun onFixChunk()
}
