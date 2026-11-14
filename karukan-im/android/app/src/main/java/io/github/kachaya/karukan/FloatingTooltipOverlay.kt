package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/**
 * カーソル位置やアンカー直下に
 * 任意の文字列メッセージやツールチップを短時間（自動消去）表示する
 * 汎用 PopupWindow オーバーレイ管理ヘルパークラスです。
 * システム・アプリのダーク/ライトモードに応じて背景色・枠色・文字色が適切に適応され、
 * 文字サイズやスタイルは Material Design 3 (Label Small / 12sp Medium) に準拠しています。
 */
class FloatingTooltipOverlay(
    private val context: Context,
    private val dpToPx: (Float) -> Int,
    private val getAnchorView: () -> View?,
    private val defaultAutoDismissDelayMs: Long = 1000L
) {

    private var popupWindow: PopupWindow? = null
    private var messageTextView: TextView? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    private fun scheduleAutoDismiss(delayMs: Long) {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            dismiss()
        }
        dismissRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private var lastX: Int = Int.MIN_VALUE
    private var lastY: Int = Int.MIN_VALUE
    private var lastWidth: Int = Int.MIN_VALUE
    private var lastHeight: Int = Int.MIN_VALUE

    companion object {
        private const val TAG = "FloatingTooltipOverlay"
    }

    private fun createPopupWindow(): PopupWindow {
        val density = context.resources.displayMetrics.density

        val backgroundColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val textColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK
        )
        val borderColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutline,
            Color.GRAY
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(backgroundColor)
                cornerRadius = 6 * density
                setStroke(dpToPx(1f), borderColor)
            }
            elevation = dpToPx(6f).toFloat()
        }

        // Material Design 3 準拠 (Label Small: 12sp Medium)
        val tv = TextView(context).apply {
            textSize = 12f
            setPadding(dpToPx(2f), dpToPx(1f), dpToPx(2f), dpToPx(1f))
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        container.addView(
            tv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        messageTextView = tv

        return PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = false
            isFocusable = false
            isClippingEnabled = false
            elevation = dpToPx(6f).toFloat()
        }
    }

    fun showTooltip(
        message: String,
        isCursorAnchorValid: Boolean,
        cursorX: Float,
        cursorYBottom: Float,
        cursorYTop: Float,
        autoDismissDelayMs: Long = defaultAutoDismissDelayMs
    ) {
        cancelAutoDismiss()
        scheduleAutoDismiss(autoDismissDelayMs)

        if (popupWindow == null) {
            popupWindow = createPopupWindow()
        }

        val tv = messageTextView ?: return
        tv.text = message

        showInternal(isCursorAnchorValid, cursorX, cursorYBottom, cursorYTop)
    }

    private fun showInternal(
        isCursorAnchorValid: Boolean,
        cursorX: Float,
        cursorYBottom: Float,
        cursorYTop: Float
    ) {
        val popup = popupWindow ?: return
        val contentView = popup.contentView ?: return

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth - dpToPx(32f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST)
        )

        val popupWidth = contentView.measuredWidth
        val popupHeight = contentView.measuredHeight

        var screenX: Int
        var screenY: Int

        if (isCursorAnchorValid && !cursorYBottom.isNaN()) {
            if (cursorX.isNaN()) {
                screenX = dpToPx(16f)
            } else {
                screenX = cursorX.toInt()
                screenX = screenX.coerceIn(dpToPx(16f), maxOf(dpToPx(16f), screenWidth - popupWidth - dpToPx(16f)))
            }

            screenY = cursorYBottom.toInt() + dpToPx(4f)
            if (screenY + popupHeight > screenHeight - dpToPx(8f)) {
                screenY = if (!cursorYTop.isNaN()) {
                    cursorYTop.toInt() - popupHeight - dpToPx(4f)
                } else {
                    screenHeight - popupHeight - dpToPx(8f)
                }
            }
        } else {
            screenX = dpToPx(16f)
            val anchor = getAnchorView()
            val loc = IntArray(2)
            if (anchor != null && anchor.isShown) {
                anchor.getLocationOnScreen(loc)
                screenY = loc[1] - popupHeight - dpToPx(8f)
            } else {
                screenY = screenHeight - popupHeight - dpToPx(100f)
            }
        }

        screenY = maxOf(0, screenY)

        if (popup.isShowing &&
            kotlin.math.abs(lastX - screenX) < 3 &&
            kotlin.math.abs(lastY - screenY) < 3 &&
            kotlin.math.abs(lastWidth - popupWidth) < 3 &&
            kotlin.math.abs(lastHeight - popupHeight) < dpToPx(16f)
        ) {
            return
        }

        lastX = screenX
        lastY = screenY
        lastWidth = popupWidth
        lastHeight = popupHeight

        showPopupAtScreenLocationWithSize(popup, screenX, screenY, popupWidth, popupHeight)
    }

    private fun showPopupAtScreenLocationWithSize(
        popup: PopupWindow,
        screenX: Int,
        screenY: Int,
        width: Int,
        height: Int
    ) {
        val anchor = getAnchorView() ?: return
        if (anchor.windowToken == null || !anchor.isAttachedToWindow) {
            if (popup.isShowing) {
                try {
                    popup.dismiss()
                } catch (_: Exception) {
                }
            }
            return
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)

        val x = screenX - loc[0]
        val y = screenY - loc[1]

        try {
            popup.isClippingEnabled = false
            if (popup.isShowing) {
                popup.update(x, y, width, height)
            } else {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
                popup.update(x, y, width, height)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show floating tooltip popup", e)
        }
    }

    fun dismiss() {
        cancelAutoDismiss()
        popupWindow?.dismiss()
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastWidth = Int.MIN_VALUE
        lastHeight = Int.MIN_VALUE
    }
}
