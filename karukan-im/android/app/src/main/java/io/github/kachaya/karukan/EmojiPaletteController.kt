package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
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
    private val keyButtonHeightSupplier: () -> Int = { keyboardHeightPxSupplier() / 5 },
    private val bottomActionSpecs: List<KeySpec> = emptyList(),
    private val createKeyButton: ((KeySpec) -> KeyButton)? = null,
    private val setupRepeatableTouchListener: ((KeyButton) -> Unit)? = null,
    private val onEmojiSelected: (String) -> Unit,
    private val onCloseRequested: () -> Unit,
    private val onLeft: () -> Unit = {},
    private val onDown: () -> Unit = {},
    private val onUp: () -> Unit = {},
    private val onRight: () -> Unit = {},
    private val onBackspace: () -> Unit = {},
    private val onEnter: () -> Unit = {},
    private val onFeedback: () -> Unit = {}
) {

    // ---------------------------------------------------------------------------------------------
    // Region: Theme & Color Caching
    // ---------------------------------------------------------------------------------------------

    fun getThemeColors(): ThemeColors {
        return ThemeColors.from(isDarkThemeSupplier())
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Properties & State
    // ---------------------------------------------------------------------------------------------

    lateinit var container: LinearLayout
        private set

    private lateinit var headerLayout: LinearLayout
    private lateinit var emojiCategoryScrollContainer: HorizontalScrollView
    private lateinit var emojiCategoryLayout: LinearLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: EmojiPagerAdapter
    private lateinit var bottomActionLayout: LinearLayout
    private val bottomButtons: MutableList<KeyButton> = mutableListOf()

    var isVisible: Boolean = true
        private set

    private var selectedCategoryIndex: Int = 0
    private var emojiCategoriesList: List<Pair<String, List<String>>> = emptyList()
    private val categoryButtons: MutableList<TextView> = mutableListOf()
    private var isSymbolMode: Boolean = false

    private val sharedRecycledViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 40)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private fun calculateSingleRowHeight(context: Context): Int {
            val tv = TextView(context).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 10)
                text = "😊"
            }
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return tv.measuredHeight
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Emoji Recent History Management
    // ---------------------------------------------------------------------------------------------

    private fun getRecentEmojis(): List<String> {
        val prefs = context.getSharedPreferences("karukan_emoji_recent_prefs", Context.MODE_PRIVATE)
        val csv = prefs.getString("recent_emojis", "") ?: ""
        if (csv.isEmpty()) return emptyList()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun addRecentEmoji(emoji: String) {
        if (emoji.isBlank()) return
        val current = getRecentEmojis().toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        val trimmed = if (current.size > 40) current.take(40) else current
        val csv = trimmed.joinToString(",")
        context.getSharedPreferences("karukan_emoji_recent_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_emojis", csv)
            .apply()

        updateRecentCategoryList(trimmed)
    }

    private fun updateRecentCategoryList(recentList: List<String>) {
        if (isSymbolMode) return
        val recentCategoryName = "履歴"

        if (emojiCategoriesList.isNotEmpty() && emojiCategoriesList.first().first == recentCategoryName) {
            val updated = emojiCategoriesList.toMutableList()
            updated[0] = recentCategoryName to recentList
            emojiCategoriesList = updated
            if (::pagerAdapter.isInitialized) {
                pagerAdapter.submitCategories(emojiCategoriesList)
            }
        }
    }

    private fun handleEmojiSelection(emoji: String) {
        addRecentEmoji(emoji)
        onEmojiSelected(emoji)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Layout Construction
    // ---------------------------------------------------------------------------------------------

    fun createView(): LinearLayout {
        val colors = getThemeColors()
        val rowHeight = keyButtonHeightSupplier()

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyboardHeightPxSupplier()
            )
        }

        // 1. ViewPager2
        pagerAdapter = EmojiPagerAdapter(sharedRecycledViewPool, ::getThemeColors, keyButtonHeightSupplier, ::handleEmojiSelection, onFeedback)

        viewPager = ViewPager2(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = pagerAdapter
            (getChildAt(0) as? RecyclerView)?.itemAnimator = null
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

        // 2. ボトムアクションバー
        bottomActionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.candidateBg)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeight
            )
        }

        bottomButtons.clear()
        val specs = if (bottomActionSpecs.isNotEmpty()) bottomActionSpecs else listOf(
            KeySpec("×", KeyType.NORMAL),
            KeySpec("←", KeyType.DPAD_LEFT, repeatable = true),
            KeySpec("↓", KeyType.DPAD_DOWN, repeatable = true),
            KeySpec("↑", KeyType.DPAD_UP, repeatable = true),
            KeySpec("→", KeyType.DPAD_RIGHT, repeatable = true),
            KeySpec("⌫", KeyType.BACKSPACE, repeatable = true),
            KeySpec("", KeyType.ENTER, R.drawable.outline_send_24)
        )

        val keyActionTable: Map<KeyType, () -> Unit> = mapOf(
            KeyType.NORMAL to { onCloseRequested() },
            KeyType.DPAD_LEFT to { onLeft() },
            KeyType.DPAD_DOWN to { onDown() },
            KeyType.DPAD_UP to { onUp() },
            KeyType.DPAD_RIGHT to { onRight() },
            KeyType.BACKSPACE to { onBackspace() },
            KeyType.ENTER to { onEnter() }
        )

        for (spec in specs) {
            val btn = createKeyButton?.invoke(spec) ?: KeyButton(
                context = context,
                spec = spec,
                keyHeight = rowHeight,
                keyTextColor = colors.keyText,
                isDark = colors.isDark,
                onFeedback = {}
            ).apply {
                updateBackgroundType(KeyBackgroundType.ACTION, colors.isDark)
            }

            if (spec.repeatable) {
                setupRepeatableTouchListener?.invoke(btn)
            }

            btn.updateBackgroundType(KeyBackgroundType.ACTION, colors.isDark)
            btn.setOnClickListener {
                keyActionTable[btn.currentType]?.invoke()
            }
            bottomButtons.add(btn)
            bottomActionLayout.addView(btn)
        }
        container.addView(bottomActionLayout)

        show(isHardKeyboard = false, symbolMode = false)
        return container
    }

    fun createHeaderView(): LinearLayout {
        ensureCategoriesLoaded()
        val colors = getThemeColors()
        headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.candidateBg)
            setPadding(4, 2, 4, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    renderContent()
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        emojiCategoryScrollContainer = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        emojiCategoryLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
        }
        emojiCategoryScrollContainer.addView(emojiCategoryLayout)
        headerLayout.addView(emojiCategoryScrollContainer)

        buildCategoryTabsOnce(colors)
        renderContent()
        return headerLayout
    }

    private fun ensureCategoriesLoaded() {
        if (emojiCategoriesList.isNotEmpty()) return
        if (isSymbolMode) {
            val symbolCategories = listOf(
                "記号" to listOf("!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/", ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^", "_", "`", "{", "|", "}", "~"),
                "数字" to listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
                "英字" to listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")
            )
            emojiCategoriesList = symbolCategories.filter { cat -> cat.second.all { it.codePointAt(0) < 127 } }
        } else {
            val rawCategories = engine.getEmojiCategories()
            val categoryList = mutableListOf<Pair<String, List<String>>>()

            val recents = getRecentEmojis()
            categoryList.add("履歴" to recents)

            if (rawCategories.isNotEmpty()) {
                categoryList.addAll(rawCategories.map { it.key to it.value })
            } else {
                val paint = Paint()
                val fallbackEmojis = listOf("😊", "👍", "🎉", "❤️", "✨", "⭐", "🔥", "💡", "🎵", "👀", "🙏", "🙌", "😭", "🤣", "♪", "★", "○", "◎", "▲", "※")
                    .filter { paint.hasGlyph(it) }
                categoryList.add("顔・感情" to fallbackEmojis)
            }
            emojiCategoriesList = categoryList
        }
    }

    private fun buildCategoryTabsOnce(colors: ThemeColors) {
        if (categoryButtons.isNotEmpty()) return
        emojiCategoryLayout.removeAllViews()
        categoryButtons.clear()

        val paddingH = dpToPx(14)
        val paddingV = dpToPx(6)
        val marginH = dpToPx(3)

        for ((index, category) in emojiCategoriesList.withIndex()) {
            val catName = category.first
            val isSelected = index == selectedCategoryIndex
            val catBtn = TextView(context).apply {
                text = catName
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(paddingH, paddingV, paddingH, paddingV)
                background = createCategoryTabDrawable(isSelected, colors)
                setTextColor(if (isSelected) colors.activeAccentText else colors.candidateText)
                typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onFeedback()
                    if (selectedCategoryIndex != index) {
                        selectedCategoryIndex = index
                        viewPager.setCurrentItem(index, true)
                        updateTabHighlightAndScroll()
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginH, 0, marginH, 0)
            }
            categoryButtons.add(catBtn)
            emojiCategoryLayout.addView(catBtn, lp)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Visibility & Toggle Controls
    // ---------------------------------------------------------------------------------------------

    fun show(isHardKeyboard: Boolean = false, symbolMode: Boolean = false) {
        if (this.isSymbolMode != symbolMode) {
            this.isSymbolMode = symbolMode
            emojiCategoriesList = emptyList()
            categoryButtons.clear()
            selectedCategoryIndex = 0
        }
        isVisible = true

        if (::viewPager.isInitialized) {
            val vpLp = viewPager.layoutParams as? LinearLayout.LayoutParams
            if (vpLp != null) {
                vpLp.height = 0
                vpLp.weight = 1f
                viewPager.layoutParams = vpLp
            }
        }

        if (::container.isInitialized) {
            container.isVisible = true
        }
        renderContent()
    }

    fun hide() {
        isVisible = false
        if (::container.isInitialized) {
            container.isGone = true
        }
    }

    fun selectNextCategory() {
        if (emojiCategoriesList.isEmpty()) return
        val nextIndex = (selectedCategoryIndex + 1) % emojiCategoriesList.size
        selectCategory(nextIndex)
    }

    fun selectPreviousCategory() {
        if (emojiCategoriesList.isEmpty()) return
        val prevIndex = if (selectedCategoryIndex - 1 < 0) emojiCategoriesList.size - 1 else selectedCategoryIndex - 1
        selectCategory(prevIndex)
    }

    private var focusedEmojiIndex: Int = 0

    fun moveGridSelection(dx: Int, dy: Int) {
        if (emojiCategoriesList.isEmpty() || selectedCategoryIndex !in emojiCategoriesList.indices) return
        val emojis = emojiCategoriesList[selectedCategoryIndex].second
        if (emojis.isEmpty()) return

        val delta = dx + (dy * 8)
        val newIndex = (focusedEmojiIndex + delta).coerceIn(0, emojis.size - 1)
        if (focusedEmojiIndex != newIndex) {
            focusedEmojiIndex = newIndex
            notifyCurrentPageFocusedIndexChanged()
        }
    }

    fun commitSelectedEmoji() {
        if (emojiCategoriesList.isEmpty() || selectedCategoryIndex !in emojiCategoriesList.indices) return
        val emojis = emojiCategoriesList[selectedCategoryIndex].second
        if (focusedEmojiIndex in emojis.indices) {
            val emoji = emojis[focusedEmojiIndex]
            onFeedback()
            handleEmojiSelection(emoji)
        }
    }

    private fun notifyCurrentPageFocusedIndexChanged() {
        if (!::viewPager.isInitialized) return
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(selectedCategoryIndex) as? EmojiPageViewHolder ?: return
        viewHolder.setFocusedIndex(focusedEmojiIndex)
    }

    fun selectCategory(index: Int) {
        if (index !in emojiCategoriesList.indices) return
        if (selectedCategoryIndex != index) {
            selectedCategoryIndex = index
            focusedEmojiIndex = 0
            if (::viewPager.isInitialized) {
                viewPager.setCurrentItem(index, false)
            }
            updateTabHighlightAndScroll()
            notifyCurrentPageFocusedIndexChanged()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Content Rendering & Tab Highlights
    // ---------------------------------------------------------------------------------------------

    fun renderContent() {
        if (!::emojiCategoryLayout.isInitialized || !::viewPager.isInitialized) return
        ensureCategoriesLoaded()

        val colors = getThemeColors()

        if (::container.isInitialized) {
            container.setBackgroundColor(colors.bg)
        }
        if (::headerLayout.isInitialized) {
            headerLayout.setBackgroundColor(colors.candidateBg)
        }
        if (::bottomActionLayout.isInitialized) {
            bottomActionLayout.setBackgroundColor(colors.candidateBg)
        }
        bottomButtons.forEach { btn ->
            btn.currentTextColor = colors.keyText
            btn.updateBackgroundType(KeyBackgroundType.ACTION, colors.isDark)
        }

        if (categoryButtons.isEmpty()) {
            buildCategoryTabsOnce(colors)
        } else {
            updateTabHighlightAndScroll()
        }

        pagerAdapter.submitCategories(emojiCategoriesList)
        if (selectedCategoryIndex in emojiCategoriesList.indices) {
            viewPager.setCurrentItem(selectedCategoryIndex, false)
            updateTabHighlightAndScroll()
        }
    }

    private fun createCategoryTabDrawable(isSelected: Boolean, colors: ThemeColors): android.graphics.drawable.Drawable {
        if (!isSelected) {
            return android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }

        val accentColor = colors.activeAccentText
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 24))
            cornerRadius = dpToPx(6).toFloat()
        }

        val indicatorDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(accentColor)
            cornerRadius = dpToPx(2).toFloat()
        }

        val layers = arrayOf(bgDrawable, indicatorDrawable)
        val layerDrawable = android.graphics.drawable.LayerDrawable(layers)

        val indicatorHeightPx = dpToPx(3)
        layerDrawable.setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        layerDrawable.setLayerHeight(1, indicatorHeightPx)
        layerDrawable.setLayerInsetLeft(1, dpToPx(6))
        layerDrawable.setLayerInsetRight(1, dpToPx(6))

        return layerDrawable
    }

    private fun updateTabHighlightAndScroll() {
        if (categoryButtons.isEmpty() || selectedCategoryIndex !in categoryButtons.indices) return

        val colors = getThemeColors()

        for ((idx, btn) in categoryButtons.withIndex()) {
            val isSelected = idx == selectedCategoryIndex
            btn.background = createCategoryTabDrawable(isSelected, colors)
            btn.setTextColor(if (isSelected) colors.activeAccentText else colors.candidateText)
            btn.typeface = if (isSelected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }

        val selectedTabBtn = categoryButtons[selectedCategoryIndex]
        emojiCategoryScrollContainer.post {
            val scrollX = selectedTabBtn.left - (emojiCategoryScrollContainer.width - selectedTabBtn.width) / 2
            emojiCategoryScrollContainer.smoothScrollTo(scrollX.coerceAtLeast(0), 0)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: ViewPager & RecyclerView Adapters
    // ---------------------------------------------------------------------------------------------

    private class EmojiPagerAdapter(
        private val sharedViewPool: RecyclerView.RecycledViewPool,
        private val getThemeColors: () -> ThemeColors,
        private val keyButtonHeightSupplier: () -> Int,
        private val onEmojiSelected: (String) -> Unit,
        private val onFeedback: () -> Unit
    ) : RecyclerView.Adapter<EmojiPageViewHolder>() {

        private var categories: List<Pair<String, List<String>>> = emptyList()

        fun submitCategories(newCategories: List<Pair<String, List<String>>>) {
            if (categories != newCategories) {
                categories = newCategories
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = categories.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiPageViewHolder {
            val context = parent.context
            val spanCount = 8

            val recyclerView = RecyclerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                layoutManager = GridLayoutManager(context, spanCount)
                setHasFixedSize(true)
                setRecycledViewPool(sharedViewPool)
                itemAnimator = null
            }
            return EmojiPageViewHolder(recyclerView, getThemeColors, keyButtonHeightSupplier, onEmojiSelected, onFeedback)
        }

        override fun onBindViewHolder(holder: EmojiPageViewHolder, position: Int) {
            val categoryItem = categories[position]
            holder.bind(categoryItem.second)
        }
    }

    private fun createEmojiFocusDrawable(isFocused: Boolean, colors: ThemeColors): android.graphics.drawable.Drawable {
        if (!isFocused) {
            return android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val accentColor = colors.activeAccentText
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 35))
            setStroke(dpToPx(2), accentColor)
            cornerRadius = dpToPx(8).toFloat()
        }
    }

    private class EmojiPageViewHolder(
        private val recyclerView: RecyclerView,
        getThemeColors: () -> ThemeColors,
        keyButtonHeightSupplier: () -> Int,
        onEmojiSelected: (String) -> Unit,
        onFeedback: () -> Unit
    ) : RecyclerView.ViewHolder(recyclerView) {

        private val gridAdapter = EmojiGridAdapter(
            getThemeColors = getThemeColors,
            keyButtonHeightSupplier = keyButtonHeightSupplier,
            onClick = onEmojiSelected,
            onFeedback = onFeedback
        )

        init {
            recyclerView.adapter = gridAdapter
        }

        fun bind(emojis: List<String>) {
            gridAdapter.submitEmojis(emojis)
        }

        fun setFocusedIndex(focusedIndex: Int) {
            gridAdapter.setFocusedIndex(focusedIndex)
            recyclerView.scrollToPosition(focusedIndex.coerceAtLeast(0))
        }
    }

    private class EmojiGridAdapter(
        private var emojis: List<String> = emptyList(),
        private val getThemeColors: () -> ThemeColors,
        private val keyButtonHeightSupplier: () -> Int,
        private val onClick: (String) -> Unit,
        private val onFeedback: () -> Unit
    ) : RecyclerView.Adapter<EmojiViewHolder>() {

        private var focusedIndex: Int = 0

        fun submitEmojis(newEmojis: List<String>) {
            if (emojis != newEmojis) {
                emojis = newEmojis
                notifyDataSetChanged()
            }
        }

        fun setFocusedIndex(newIndex: Int) {
            if (focusedIndex != newIndex) {
                val oldIndex = focusedIndex
                focusedIndex = newIndex
                notifyItemChanged(oldIndex)
                notifyItemChanged(newIndex)
            }
        }

        override fun getItemCount(): Int = emojis.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
            val colors = getThemeColors()
            val rowHeight = keyButtonHeightSupplier()
            val textView = TextView(parent.context).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                setTextColor(colors.textColor)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
            }
            return EmojiViewHolder(textView, onClick, onFeedback)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            val colors = getThemeColors()
            val isFocused = position == focusedIndex
            holder.bind(emojis[position], colors.textColor, isFocused, colors)
        }
    }

    private class EmojiViewHolder(
        private val textView: TextView,
        private val onClick: (String) -> Unit,
        private val onFeedback: () -> Unit
    ) : RecyclerView.ViewHolder(textView) {

        fun bind(emoji: String, textColor: Int, isFocused: Boolean, colors: ThemeColors) {
            textView.text = emoji
            textView.setTextColor(textColor)
            textView.background = if (isFocused) {
                val accentColor = colors.activeAccentText
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 35))
                    setStroke((2 * textView.resources.displayMetrics.density).toInt(), accentColor)
                    cornerRadius = (8 * textView.resources.displayMetrics.density)
                }
            } else {
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            textView.setOnClickListener {
                onFeedback()
                onClick(emoji)
            }
        }
    }
}
