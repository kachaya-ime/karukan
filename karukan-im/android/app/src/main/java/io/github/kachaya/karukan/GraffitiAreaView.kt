package io.github.kachaya.karukan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Graffiti 手書き入力エリアのカスタム View。
 * 左側が英字 (ALPHA) エリア、右側が数字 (NUMERIC) エリア。
 * タッチ軌跡のリアルタイム描画とストローク終了時の認識処理を行います。
 */
@SuppressLint("ViewConstructor")
class GraffitiAreaView(
    context: Context,
    private val isDarkSupplier: () -> Boolean,
    private val onStrokeRecognized: (GraffitiStrokeRecognizer.RecognitionResult) -> Unit,
    private val onStrokeHelpRequested: () -> Unit
) : View(context) {

    // ---------------------------------------------------------------------------------------------
    // Region: Properties & Paints
    // ---------------------------------------------------------------------------------------------

    private val currentPoints = mutableListOf<GraffitiStrokeRecognizer.Point>()
    private var isShiftMode: Boolean = false
    private var isCapsMode: Boolean = false

    private val strokePath = Path()
    private val recognitionPath = Path()

    // 描画用 Paint
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 8f
    }

    private val startPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    private val guideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val modeIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.LEFT
    }

    private val helpButtonBounds = RectF()
    private val helpButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val helpTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        isFocusable = true
        GraffitiStrokeRecognizer.loadTemplatesFromContext(context)
    }

    fun resetMode() {
        GraffitiStrokeRecognizer.isPunctuationMode = false
        isShiftMode = false
        isCapsMode = false
        invalidate()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Drawing & Rendering
    // ---------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val isDark = isDarkSupplier()
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val bgColor = if (isDark) Color.parseColor("#1F2023") else Color.parseColor("#F1F3F4")
        val dividerColor = if (isDark) Color.parseColor("#5F6368") else Color.parseColor("#BDC1C6")
        val guideTextColor = if (isDark) Color.parseColor("#444950") else Color.parseColor("#D0D4D9")
        val strokeColor = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")
        val indicatorColor = if (isDark) Color.parseColor("#F28B82") else Color.parseColor("#D93025")

        canvas.drawColor(bgColor)

        val midX = width / 2f
        dividerPaint.color = dividerColor
        canvas.drawLine(midX, 16f, midX, height - 16f, dividerPaint)

        guideTextPaint.color = guideTextColor
        canvas.drawText("ABC", midX / 2f, height / 2f + 12f, guideTextPaint)
        canvas.drawText("123", midX + midX / 2f, height / 2f + 12f, guideTextPaint)

        modeIndicatorPaint.color = indicatorColor
        var modeText = ""
        if (isCapsMode) {
            modeText += "[CAPS] "
        } else if (isShiftMode) {
            modeText += "[SHIFT] "
        }
        if (GraffitiStrokeRecognizer.isPunctuationMode) {
            modeText += "[PUNC •] "
        }
        if (modeText.isNotEmpty()) {
            canvas.drawText(modeText, 24f, 44f, modeIndicatorPaint)
        }

        val btnW = 56f
        val btnH = 56f
        helpButtonBounds.set(width - btnW - 16f, 16f, width - 16f, 16f + btnH)
        helpButtonPaint.color = if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0")
        canvas.drawRoundRect(helpButtonBounds, 12f, 12f, helpButtonPaint)
        helpTextPaint.color = if (isDark) Color.WHITE else Color.BLACK
        canvas.drawText("?", helpButtonBounds.centerX(), helpButtonBounds.centerY() + 10f, helpTextPaint)

        if (currentPoints.isNotEmpty()) {
            strokePaint.color = strokeColor
            canvas.drawPath(strokePath, strokePaint)

            startPointPaint.color = strokeColor
            val first = currentPoints.first()
            canvas.drawCircle(first.x, first.y, 10f, startPointPaint)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Motion Event & Stroke Processing
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (helpButtonBounds.contains(x, y)) {
                    onStrokeHelpRequested()
                    return true
                }

                currentPoints.clear()
                strokePath.reset()
                strokePath.moveTo(x, y)
                recognitionPath.reset()
                recognitionPath.moveTo(x, y)
                currentPoints.add(GraffitiStrokeRecognizer.Point(x, y))
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(GraffitiStrokeRecognizer.Point(x, y))
                strokePath.lineTo(x, y)
                recognitionPath.lineTo(x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (currentPoints.isNotEmpty()) {
                    currentPoints.add(GraffitiStrokeRecognizer.Point(x, y))
                    strokePath.lineTo(x, y)
                    recognitionPath.lineTo(x, y)
                    processStroke()
                }
                currentPoints.clear()
                strokePath.reset()
                recognitionPath.reset()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                currentPoints.clear()
                strokePath.reset()
                recognitionPath.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun processStroke() {
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val result = GraffitiStrokeRecognizer.recognize(
            path = recognitionPath,
            bounds = bounds
        )
        when (result) {
            is GraffitiStrokeRecognizer.RecognitionResult.PunctuationShift -> {
                invalidate()
            }

            is GraffitiStrokeRecognizer.RecognitionResult.Shift -> {
                if (isCapsMode) {
                    isCapsMode = false
                    isShiftMode = false
                } else if (isShiftMode) {
                    isCapsMode = true
                    isShiftMode = false
                } else {
                    isShiftMode = true
                }
            }

            is GraffitiStrokeRecognizer.RecognitionResult.Caps -> {
                isCapsMode = !isCapsMode
                isShiftMode = false
            }

            is GraffitiStrokeRecognizer.RecognitionResult.CharResult -> {
                var char = result.char
                if (isCapsMode || isShiftMode) {
                    char = char.uppercase()
                    isShiftMode = false
                }
                onStrokeRecognized(GraffitiStrokeRecognizer.RecognitionResult.CharResult(char))
            }

            is GraffitiStrokeRecognizer.RecognitionResult.None -> {
                // Ignore
            }

            else -> {
                onStrokeRecognized(result)
            }
        }
    }
}
