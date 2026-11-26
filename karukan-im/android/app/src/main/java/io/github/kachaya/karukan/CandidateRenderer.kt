package io.github.kachaya.karukan

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 入力ビュー内の候補一覧（水平バー・展開フローレイアウト）と
 * フローティング候補一覧（フローレイアウト）で候補ビューの生成・描画・スタイリングを共通化するレンダラークラスです。
 */
object CandidateRenderer {

    fun render(
        container: ViewGroup,
        candidates: List<String>,
        selectedIndex: Int,
        colors: ThemeColors,
        showAnnotations: Boolean,
        isSingleRowBar: Boolean,
        dpToPx: (Float) -> Int,
        onItemClick: (index: Int, rawCandidate: String) -> Unit
    ) {
        val existingCount = container.childCount
        val targetCount = candidates.size

        if (existingCount > targetCount) {
            container.removeViews(targetCount, existingCount - targetCount)
        }

        val context = container.context
        val paddingH = dpToPx(6f)
        val paddingV = dpToPx(6f)
        val density = context.resources.displayMetrics.density
        val cornerRadius = 6 * density
        val strokeWidth = dpToPx(1f)
        val strokeColor = colors.strokeBorderColor

        val normalBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(colors.candidateBg)
            this.cornerRadius = cornerRadius
            setStroke(strokeWidth, strokeColor)
        }

        val selectedBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(colors.candidateSelectedBg)
            this.cornerRadius = cornerRadius
            setStroke(strokeWidth, strokeColor)
        }

        for (i in 0 until targetCount) {
            val rawCandidate = candidates[i]
            val isSelected = (i == selectedIndex)

            val displayCandidate = if (showAnnotations) {
                TextTransformUtils.formatCandidateText(rawCandidate)
            } else {
                rawCandidate.extractPureCandidate()
            }

            val child = if (i < container.childCount) {
                container.getChildAt(i) as TextView
            } else {
                TextView(context).apply {
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER
                    setPadding(paddingH, paddingV, paddingH, paddingV)
                    isClickable = true
                    isFocusable = true
                    container.addView(this)
                }
            }

            child.text = displayCandidate

            val lp = if (isSingleRowBar) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (i > 0) {
                        setMargins(dpToPx(6f), 0, 0, 0)
                    } else {
                        setMargins(0, 0, 0, 0)
                    }
                }
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            child.layoutParams = lp

            if (isSelected) {
                child.background = selectedBg
                child.setTextColor(colors.candidateSelectedText)
            } else {
                child.background = normalBg
                child.setTextColor(colors.candidateText)
            }

            child.setOnClickListener {
                onItemClick(i, rawCandidate)
            }
        }
    }
}
