package io.github.kachaya.karukan

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * 物理キーボード接続時にカーソル直下に候補およびステータスを表示する
 * フローティング PopupWindow オーバーレイ管理ヘルパークラスです。
 */
class FloatingCandidateOverlay(
    private val context: Context,
    private val dpToPx: (Float) -> Int,
    private val getThemeColors: () -> ThemeColors,
    private val getAnchorView: () -> View?,
    private val isHardKeyboardConnected: () -> Boolean,
    private val isShowFloatingWindowEnabled: () -> Boolean
) {

    private var popupWindow: PopupWindow? = null
    private var statusTextView: TextView? = null
    private var dividerView: View? = null
    private var candidateScrollView: ScrollView? = null
    var candidateFlowLayout: CandidateFlowLayout? = null
        private set

    private var lastX: Int = Int.MIN_VALUE
    private var lastY: Int = Int.MIN_VALUE
    private var lastWidth: Int = Int.MIN_VALUE
    private var lastHeight: Int = Int.MIN_VALUE

    companion object {
        private const val TAG = "FloatingOverlay"
    }

    private fun createPopupWindow(): PopupWindow {
        val colors = getThemeColors()
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(colors.candidateBg)
                cornerRadius = 8 * density
                setStroke(dpToPx(1f), colors.strokeBorderColor)
            }
            elevation = dpToPx(8f).toFloat()
        }

        val statusTv = TextView(context).apply {
            textSize = 15f
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(4f))
            setTextColor(colors.textColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }

        val divView = View(context).apply {
            setBackgroundColor(colors.dividerColor)
            visibility = View.GONE
        }

        val sv = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
        }

        val flowLayout = CandidateFlowLayout(context).apply {
            horizontalGapPx = dpToPx(6f)
            verticalGapPx = dpToPx(6f)
            setPadding(0, 0, 0, dpToPx(6f))
        }

        sv.addView(
            flowLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        container.addView(statusTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(divView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
            setMargins(0, dpToPx(4f), 0, dpToPx(4f))
        })
        container.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusTextView = statusTv
        dividerView = divView
        candidateScrollView = sv
        candidateFlowLayout = flowLayout

        return PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = false
            isFocusable = false
            isClippingEnabled = false
            elevation = dpToPx(8f).toFloat()
        }
    }

    fun getOrCreateCandidateFlowLayout(): CandidateFlowLayout {
        if (popupWindow == null) {
            popupWindow = createPopupWindow()
        }
        return candidateFlowLayout!!
    }

    fun updateOverlay(
        selectedIndex: Int,
        isCursorAnchorValid: Boolean,
        cursorX: Float,
        cursorYBottom: Float,
        cursorYTop: Float
    ) {
        if (!isHardKeyboardConnected() || !isShowFloatingWindowEnabled() || selectedIndex < 0) {
            dismiss()
            return
        }

        val hasCandidates = (candidateFlowLayout?.childCount ?: 0) > 0

        if (!hasCandidates) {
            dismiss()
            return
        }

        if (popupWindow == null) {
            popupWindow = createPopupWindow()
        }

        val statusTv = statusTextView ?: return
        val sv = candidateScrollView ?: return

        statusTv.visibility = View.GONE
        sv.visibility = View.VISIBLE
        dividerView?.visibility = View.GONE

        showInternal(hasCandidates, isCursorAnchorValid, cursorX, cursorYBottom, cursorYTop)
    }

    private fun showInternal(
        isSelectingCandidates: Boolean,
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

        val singleRowHeight = dpToPx(34f)
        val rowGap = dpToPx(6f)
        val max4RowsHeight = (singleRowHeight + rowGap) * 4

        candidateFlowLayout?.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth - dpToPx(48f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val flowHeight = candidateFlowLayout?.measuredHeight ?: 0

        val svLp = candidateScrollView?.layoutParams
        if (svLp != null) {
            val targetSvHeight = if (flowHeight > max4RowsHeight) max4RowsHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            if (svLp.height != targetSvHeight) {
                svLp.height = targetSvHeight
                candidateScrollView?.layoutParams = svLp
            }
        }

        val maxPopupHeight = (screenHeight * 0.45f).toInt()
        val targetWidth = if (isSelectingCandidates) screenWidth - dpToPx(32f) else ViewGroup.LayoutParams.WRAP_CONTENT

        if (targetWidth == ViewGroup.LayoutParams.WRAP_CONTENT) {
            contentView.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth - dpToPx(32f), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxPopupHeight, View.MeasureSpec.AT_MOST)
            )
        } else {
            contentView.measure(
                View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxPopupHeight, View.MeasureSpec.AT_MOST)
            )
        }

        val popupWidth = contentView.measuredWidth
        val popupHeight = minOf(contentView.measuredHeight, maxPopupHeight)

        var screenX: Int
        var screenY: Int

        if (isCursorAnchorValid && !cursorYBottom.isNaN()) {
            if (isSelectingCandidates || cursorX.isNaN()) {
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

        if (popup.isShowing && lastX == screenX && lastY == screenY && lastWidth == popupWidth && lastHeight == popupHeight) {
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
            Log.w(TAG, "Failed to show floating popup", e)
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastWidth = Int.MIN_VALUE
        lastHeight = Int.MIN_VALUE
    }

    fun scrollToSelectedCandidate(index: Int) {
        val sv = candidateScrollView ?: return
        val flowLayout = candidateFlowLayout ?: return
        if (index < 0 || index >= flowLayout.childCount) return
        val selectedView = flowLayout.getChildAt(index) ?: return
        sv.post {
            val scrollTo = selectedView.top - (sv.height / 2) + (selectedView.height / 2)
            sv.smoothScrollTo(0, maxOf(0, scrollTo))
        }
    }
}
