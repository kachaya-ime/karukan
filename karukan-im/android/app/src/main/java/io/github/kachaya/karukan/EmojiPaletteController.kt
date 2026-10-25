package io.github.kachaya.karukan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * ViewPager2 + RecyclerView を使用して、指のタッチに完全追従する高レスポンス絵文字ピッカーを描画・制御するコントローラー
 */
class EmojiPaletteController(
    private val context: Context,
    private val engine: KarukanEngine,
    private val isDarkThemeSupplier: () -> Boolean,
    private val keyboardHeightPxSupplier: () -> Int,
    private val onEmojiSelected: (String) -> Unit,
    private val onCloseRequested: () -> Unit
) {
    lateinit var container: LinearLayout
        private set

    private lateinit var emojiCategoryScrollContainer: HorizontalScrollView
    private lateinit var emojiCategoryLayout: LinearLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: EmojiPagerAdapter

    var isVisible: Boolean = false
        private set

    private var selectedCategoryIndex: Int = 0
    private var emojiCategoriesList: List<Pair<String, List<String>>> = emptyList()
    private val categoryButtons: MutableList<Button> = mutableListOf()

    fun createView(): LinearLayout {
        val isDark = isDarkThemeSupplier()
        val bgColor = if (isDark) Color.parseColor("#202124") else Color.parseColor("#ECEFF1")
        val candidateBgColor = if (isDark) Color.parseColor("#303134") else Color.parseColor("#F8F9FA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val btnCloseBgColor = if (isDark) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0")

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyboardHeightPxSupplier()
            )
        }

        // --- ヘッダー行 (カテゴリタブ + 閉じるボタン) ---
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(candidateBgColor)
            setPadding(4, 2, 4, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        emojiCategoryScrollContainer = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isHorizontalScrollBarEnabled = false
        }
        emojiCategoryLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        emojiCategoryScrollContainer.addView(emojiCategoryLayout)
        headerLayout.addView(emojiCategoryScrollContainer)

        val btnClose = Button(context).apply {
            text = "✕ 閉じる"
            textSize = 12f
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(btnCloseBgColor)
            setPadding(12, 4, 12, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                onCloseRequested()
            }
        }
        headerLayout.addView(btnClose)
        container.addView(headerLayout)

        // --- 絵文字 ViewPager2 (各ページがカテゴリごとの RecyclerView) ---
        pagerAdapter = EmojiPagerAdapter { emoji ->
            onEmojiSelected(emoji)
        }

        viewPager = ViewPager2(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = pagerAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (selectedCategoryIndex != position) {
                        selectedCategoryIndex = position
                        updateTabHighlightAndScroll()
                    }
                }
            })
        }
        container.addView(viewPager)

        return container
    }

    fun show(targetKeyboardContainer: View) {
        isVisible = true
        val targetHeight = if (targetKeyboardContainer.height > 0) {
            targetKeyboardContainer.height
        } else if (targetKeyboardContainer.measuredHeight > 0) {
            targetKeyboardContainer.measuredHeight
        } else {
            keyboardHeightPxSupplier()
        }
        if (targetHeight > 0) {
            val lp = container.layoutParams
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight
                container.layoutParams = lp
            }
        }
        targetKeyboardContainer.visibility = View.GONE
        container.visibility = View.VISIBLE
        renderContent()
    }

    fun hide(targetKeyboardContainer: View) {
        isVisible = false
        container.visibility = View.GONE
        targetKeyboardContainer.visibility = View.VISIBLE
    }

    fun toggle(targetKeyboardContainer: View) {
        if (isVisible) {
            hide(targetKeyboardContainer)
        } else {
            show(targetKeyboardContainer)
        }
    }

    fun renderContent() {
        if (!::emojiCategoryLayout.isInitialized || !::viewPager.isInitialized) return

        if (emojiCategoriesList.isEmpty()) {
            val rawCategories = engine.getEmojiCategories()
            if (rawCategories.isNotEmpty()) {
                emojiCategoriesList = rawCategories.map { it.key to it.value }
            } else {
                val paint = Paint()
                val fallbackEmojis = listOf("😊", "👍", "🎉", "❤️", "✨", "⭐", "🔥", "💡", "🎵", "👀", "🙏", "🙌", "😭", "🤣", "♪", "★", "○", "◎", "▲", "※")
                    .filter { paint.hasGlyph(it) }
                emojiCategoriesList = listOf("顔・感情" to fallbackEmojis)
            }
        }

        emojiCategoryLayout.removeAllViews()
        categoryButtons.clear()

        val isDark = isDarkThemeSupplier()
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val activeTextColor = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")

        // 1. カテゴリタブの構築
        for ((index, category) in emojiCategoriesList.withIndex()) {
            val catName = category.first
            val catBtn = Button(context).apply {
                text = catName
                textSize = 12f
                setPadding(12, 4, 12, 4)
                setTextColor(if (index == selectedCategoryIndex) activeTextColor else textColor)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    if (selectedCategoryIndex != index) {
                        selectedCategoryIndex = index
                        viewPager.setCurrentItem(index, true)
                        updateTabHighlightAndScroll()
                    }
                }
            }
            categoryButtons.add(catBtn)
            emojiCategoryLayout.addView(catBtn)
        }

        // 2. ViewPager2 へのデータ設定
        pagerAdapter.submitCategories(emojiCategoriesList)
        if (selectedCategoryIndex in emojiCategoriesList.indices) {
            viewPager.setCurrentItem(selectedCategoryIndex, false)
            updateTabHighlightAndScroll()
        }
    }

    private fun updateTabHighlightAndScroll() {
        if (categoryButtons.isEmpty() || selectedCategoryIndex !in categoryButtons.indices) return

        val isDark = isDarkThemeSupplier()
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val activeTextColor = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")

        for ((idx, btn) in categoryButtons.withIndex()) {
            btn.setTextColor(if (idx == selectedCategoryIndex) activeTextColor else textColor)
        }

        val selectedTabBtn = categoryButtons[selectedCategoryIndex]
        emojiCategoryScrollContainer.post {
            val scrollX = selectedTabBtn.left - (emojiCategoryScrollContainer.width - selectedTabBtn.width) / 2
            emojiCategoryScrollContainer.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
        }
    }

    /**
     * 各カテゴリページ (RecyclerView) を ViewPager2 内で表示する PagerAdapter
     */
    private class EmojiPagerAdapter(
        private val onEmojiSelected: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiPageViewHolder>() {

        private var categories: List<Pair<String, List<String>>> = emptyList()

        fun submitCategories(newCategories: List<Pair<String, List<String>>>) {
            categories = newCategories
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = categories.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiPageViewHolder {
            val context = parent.context
            val displayMetrics = context.resources.displayMetrics
            val itemWidthPx = (44 * displayMetrics.density).toInt()
            val spanCount = maxOf(6, displayMetrics.widthPixels / maxOf(1, itemWidthPx))

            val recyclerView = RecyclerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layoutManager = GridLayoutManager(context, spanCount)
                setHasFixedSize(true)
            }
            return EmojiPageViewHolder(recyclerView, onEmojiSelected)
        }

        override fun onBindViewHolder(holder: EmojiPageViewHolder, position: Int) {
            val categoryItem = categories[position]
            holder.bind(categoryItem.second)
        }
    }

    private class EmojiPageViewHolder(
        private val recyclerView: RecyclerView,
        private val onEmojiSelected: (String) -> Unit
    ) : RecyclerView.ViewHolder(recyclerView) {

        fun bind(emojis: List<String>) {
            val adapter = EmojiGridAdapter(emojis, onEmojiSelected)
            recyclerView.adapter = adapter
        }
    }

    /**
     * 各カテゴリ内の絵文字 8列グリッド Adapter
     */
    private class EmojiGridAdapter(
        private val emojis: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiViewHolder>() {

        override fun getItemCount(): Int = emojis.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
            val textView = TextView(parent.context).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return EmojiViewHolder(textView, onClick)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            holder.bind(emojis[position])
        }
    }

    private class EmojiViewHolder(
        private val textView: TextView,
        private val onClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(textView) {

        fun bind(emoji: String) {
            textView.text = emoji
            textView.setOnClickListener {
                onClick(emoji)
            }
        }
    }
}