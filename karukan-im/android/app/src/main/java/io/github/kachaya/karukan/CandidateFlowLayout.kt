package io.github.kachaya.karukan

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * IME 変換候補の複数行表示（折り返しレイアウト）用カスタム ViewGroup
 */
class CandidateFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    var horizontalGapPx: Int = (6 * density).toInt()
    var verticalGapPx: Int = (6 * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var currentLineWidth = 0
        var currentLineHeight = 0
        var totalHeight = paddingTop + paddingBottom

        val childWidthSpec = MeasureSpec.makeMeasureSpec(maxOf(0, widthSize), MeasureSpec.AT_MOST)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        var hasVisibleChildInLine = false

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            child.measure(childWidthSpec, childHeightSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (hasVisibleChildInLine && currentLineWidth + horizontalGapPx + childWidth > widthSize) {
                totalHeight += currentLineHeight + verticalGapPx
                currentLineWidth = childWidth
                currentLineHeight = childHeight
            } else {
                if (hasVisibleChildInLine) {
                    currentLineWidth += horizontalGapPx
                }
                currentLineWidth += childWidth
                currentLineHeight = maxOf(currentLineHeight, childHeight)
            }
            hasVisibleChildInLine = true
        }
        totalHeight += currentLineHeight

        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = resolveSize(totalHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, maxOf(measuredHeight, paddingTop + paddingBottom))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widthSize = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var currentLineHeight = 0
        var hasVisibleChildInLine = false

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (hasVisibleChildInLine && x + horizontalGapPx + childWidth > paddingLeft + widthSize) {
                x = paddingLeft
                y += currentLineHeight + verticalGapPx
                currentLineHeight = 0
                hasVisibleChildInLine = false
            }

            if (hasVisibleChildInLine) {
                x += horizontalGapPx
            }

            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth
            currentLineHeight = maxOf(currentLineHeight, childHeight)
            hasVisibleChildInLine = true
        }
    }
}
