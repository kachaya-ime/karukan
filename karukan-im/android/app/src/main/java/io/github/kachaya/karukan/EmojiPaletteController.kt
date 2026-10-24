package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView + GridLayoutManager を使用して高速・高レスポンスに絵文字ピッカーを描画・制御するコントローラー
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
    private lateinit var recyclerView: RecyclerView
    private lateinit var emojiAdapter: EmojiAdapter

    var isVisible: Boolean = false
        private set

    private var selectedEmojiCategory: String? = null
    private var emojiCategories: Map<String, List<String>> = emptyMap()

    fun createView(): LinearLayout {
        val isDark = isDarkThemeSupplier()
        val bgColor = if (isDark) context.resources.getColor(R.color.keyboard_bg, context.theme) else Color.parseColor("#f1f3f4")
        val candidateBgColor = if (isDark) context.resources.getColor(R.color.candidate_bg, context.theme) else Color.parseColor("#e8eaed")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")

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

        // --- 絵文字グリッド用 RecyclerView (8列) ---
        emojiAdapter = EmojiAdapter { emoji ->
            onEmojiSelected(emoji)
        }

        recyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = GridLayoutManager(context, 8)
            adapter = emojiAdapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        container.addView(recyclerView)

        return container
    }

    fun show(targetKeyboardContainer: View) {
        isVisible = true
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
        if (!::emojiCategoryLayout.isInitialized || !::recyclerView.isInitialized) return

        if (emojiCategories.isEmpty()) {
            emojiCategories = engine.getEmojiCategories()
        }

        if (emojiCategories.isEmpty()) {
            val fallbackEmojis = listOf("😊", "👍", "🎉", "❤️", "✨", "⭐", "🔥", "💡", "🎵", "👀", "🙏", "🙌", "😭", "🤣", "♪", "★", "○", "◎", "▲", "※")
            emojiAdapter.submitEmojis(fallbackEmojis)
            return
        }

        val categoriesList = emojiCategories.keys.toList()
        if (selectedEmojiCategory == null || !emojiCategories.containsKey(selectedEmojiCategory)) {
            selectedEmojiCategory = categoriesList.firstOrNull()
        }

        emojiCategoryLayout.removeAllViews()

        val isDark = isDarkThemeSupplier()
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val activeTextColor = if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")

        // 1. カテゴリタブの追加
        for (catName in categoriesList) {
            val isSelected = catName == selectedEmojiCategory
            val catBtn = Button(context).apply {
                text = catName
                textSize = 12f
                setPadding(12, 4, 12, 4)
                setTextColor(if (isSelected) activeTextColor else textColor)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    if (selectedEmojiCategory != catName) {
                        selectedEmojiCategory = catName
                        renderContent()
                    }
                }
            }
            emojiCategoryLayout.addView(catBtn)
        }

        // 2. 選択中カテゴリの絵文字を RecyclerView へ供給 (スクロール位置を最上部へリセット)
        val currentEmojis = emojiCategories[selectedEmojiCategory] ?: emptyList()
        emojiAdapter.submitEmojis(currentEmojis)
        recyclerView.scrollToPosition(0)
    }

    /**
     * 高速絵文字表示用 RecyclerView Adapter
     */
    private class EmojiAdapter(
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiViewHolder>() {

        private var emojis: List<String> = emptyList()

        fun submitEmojis(newEmojis: List<String>) {
            emojis = newEmojis
            notifyDataSetChanged()
        }

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

        override fun getItemCount(): Int = emojis.size
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