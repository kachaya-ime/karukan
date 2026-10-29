package io.github.kachaya.karukan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.StateSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Karukan IME の入力ビュー（画面下部キーボード UI & 変換候補パネル & フローティング表示）を管理するカスタムビュークラスです。
 */
class KarukanInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun isDarkTheme(): Boolean
        fun isHardKeyboardConnected(): Boolean
        fun is12KeyHardKeyboardConnected(): Boolean = false
        fun getKeyboardHeightPx(): Int
        fun triggerKeyFeedback()
        fun onCandidateSelected(hiragana: String, candidate: String, index: Int)
        fun onEmojiSelected(emoji: String)
        fun getKeyboardEventListener(): KeyboardLayoutBuilder.KeyboardEventListener
        fun getSourceFilterLabel(filter: KarukanInputMethodService.SourceFilter): String
        fun getActiveSourceFilter(): KarukanInputMethodService.SourceFilter
        fun getEngine(): KarukanEngine
    }

    private var listener: Listener? = null
    private var keyboardLayoutSetting: String = "qwerty"
    private var inputModeState: InputMode = InputMode.HIRAGANA

    // 未確定テキスト表示エリア
    lateinit var composingTextView: TextView
        private set

    // 候補表示領域関連 (画面下部)
    private lateinit var candidateBarContainer: LinearLayout
    private lateinit var candidateScrollContainer: HorizontalScrollView
    private lateinit var candidateLayout: LinearLayout
    private lateinit var expandButton: Button
    var isCandidateExpanded: Boolean = false
        private set

    // 現在の候補キャッシュ (Lazyレンダリング・ビュー再利用用)
    private var lastHiragana: String = ""
    private var lastCandidates: List<String> = emptyList()
    private var lastSelectedIndex: Int = -1

    // 複数行候補表示パネル領域
    private lateinit var candidateExpandedContainer: LinearLayout
    private lateinit var candidateFlowLayout: CandidateFlowLayout
    private lateinit var expandedScrollView: ScrollView

    // キーボード & 絵文字パレット
    lateinit var mainKeyboardContainer: View
        private set
    private var keyboardLayoutBuilder: KeyboardLayoutBuilder? = null
    private lateinit var emojiPaletteContainer: LinearLayout
    private lateinit var emojiPaletteController: EmojiPaletteController

    // フローティングオーバーレイ表示関連 (物理キーボード接続時用)
    private var floatingPopupWindow: PopupWindow? = null
    private var floatingStatusTextView: TextView? = null
    private var floatingCandidateScrollView: ScrollView? = null
    private var floatingCandidateFlowLayout: CandidateFlowLayout? = null
    private var lastFloatingX: Int = Int.MIN_VALUE
    private var lastFloatingY: Int = Int.MIN_VALUE
    private var lastFloatingWidth: Int = Int.MIN_VALUE
    private var lastFloatingHeight: Int = Int.MIN_VALUE
    private var lastFloatingStatusText: String = ""
    private var lastFloatingSelecting: Boolean = false

    var isCursorAnchorValid: Boolean = false
        private set
    var cursorX: Float = Float.NaN
        private set
    var cursorYBottom: Float = Float.NaN
        private set
    var cursorYTop: Float = Float.NaN
        private set

    fun updateCursorPosition(isValid: Boolean, x: Float, yBottom: Float, yTop: Float) {
        this.isCursorAnchorValid = isValid
        this.cursorX = x
        this.cursorYBottom = yBottom
        this.cursorYTop = yTop
    }

    companion object {
        private const val TAG = "KarukanInputView"
    }

    data class ThemeColors(
        val keyboardBg: Int,
        val candidateBg: Int,
        val textColor: Int,
        val candidateBtnBg: Int
    )

    fun getThemeColors(): ThemeColors {
        val isDark = listener?.isDarkTheme() ?: true
        return if (isDark) {
            ThemeColors(
                keyboardBg = Color.parseColor("#202124"),
                candidateBg = Color.parseColor("#303134"),
                textColor = Color.WHITE,
                candidateBtnBg = Color.parseColor("#3C4043")
            )
        } else {
            ThemeColors(
                keyboardBg = Color.parseColor("#ECEFF1"),
                candidateBg = Color.parseColor("#F8F9FA"),
                textColor = Color.parseColor("#202124"),
                candidateBtnBg = Color.WHITE
            )
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    fun initView(
        listener: Listener,
        keyboardLayoutBuilder: KeyboardLayoutBuilder,
        keyboardLayoutSetting: String,
        isEnglishMode: Boolean
    ) {
        this.listener = listener
        this.keyboardLayoutBuilder = keyboardLayoutBuilder.apply {
            onContainerSwapRequested = { newContainer ->
                val index = indexOfChild(mainKeyboardContainer)
                if (index != -1) {
                    removeView(mainKeyboardContainer)
                    mainKeyboardContainer = newContainer
                    addView(mainKeyboardContainer, index, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 0)
                    })
                }
            }
        }
        this.keyboardLayoutSetting = keyboardLayoutSetting
        val colors = getThemeColors()

        orientation = VERTICAL
        setBackgroundColor(colors.keyboardBg)

        val initialPaddingLeft = paddingLeft
        val initialPaddingTop = paddingTop
        val initialPaddingRight = paddingRight
        val initialPaddingBottom = paddingBottom

        val applyInsets: (View, WindowInsetsCompat) -> Unit = { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navInsets.bottom
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            applyInsets(view, insets)
            insets
        }

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.getRootWindowInsets(v)?.let { rootInsets ->
                    applyInsets(v, rootInsets)
                }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        requestApplyInsets()

        // 1. 未確定テキスト表示エリア
        composingTextView = TextView(context).apply {
            text = if (isEnglishMode) "英数モード" else "かな: (なし)"
            textSize = 14f
            setPadding(dpToPx(16f), dpToPx(8f), dpToPx(16f), dpToPx(8f))
            setTextColor(colors.textColor)
            setBackgroundColor(colors.keyboardBg)
        }
        addView(composingTextView)

        // 2. 変換候補バー (横スクロール可能) + 展開ボタン (▼)
        candidateBarContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(colors.candidateBg)
            gravity = Gravity.CENTER_VERTICAL
        }

        candidateScrollContainer = HorizontalScrollView(context).apply {
            setBackgroundColor(colors.candidateBg)
            setPadding(dpToPx(4f), dpToPx(2f), dpToPx(4f), dpToPx(2f))
        }
        candidateLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        val defaultCandidateTextView = TextView(context).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
        candidateScrollContainer.addView(candidateLayout)

        candidateBarContainer.addView(
            candidateScrollContainer,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        expandButton = Button(ContextThemeWrapper(context, R.style.Theme_Karukan)).apply {
            text = "▼"
            textSize = 14f
            isAllCaps = false
            minHeight = dpToPx(32f)
            minimumHeight = dpToPx(32f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
            backgroundTintList = ColorStateList.valueOf(colors.candidateBtnBg)
            setTextColor(colors.textColor)
            visibility = GONE
            setOnClickListener {
                listener.triggerKeyFeedback()
                toggleCandidateExpand()
            }
        }
        val expandBtnParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpToPx(4f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
        }
        candidateBarContainer.addView(expandButton, expandBtnParams)

        addView(candidateBarContainer)

        // 3. 画面キーボードの構築
        mainKeyboardContainer = when (keyboardLayoutSetting) {
            "niko" -> keyboardLayoutBuilder.createNikoKeyboardContainer()
            "tablet" -> keyboardLayoutBuilder.createTabletKeyboardContainer()
            else -> keyboardLayoutBuilder.createQwertyKeyboardContainer()
        }
        addView(mainKeyboardContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 0)
        })

        // 4. 複数行変換候補表示エリア
        candidateExpandedContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(colors.keyboardBg)
            visibility = GONE
        }

        expandedScrollView = ScrollView(context).apply {
            isFillViewport = true
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }

        candidateFlowLayout = CandidateFlowLayout(context).apply {
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
        }

        expandedScrollView.addView(
            candidateFlowLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        candidateExpandedContainer.addView(
            expandedScrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val initialKbHeight = listener.getKeyboardHeightPx()
        addView(
            candidateExpandedContainer,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                if (initialKbHeight > 0) initialKbHeight else LayoutParams.WRAP_CONTENT
            )
        )

        // 5. 絵文字パレット領域の構築
        emojiPaletteController = EmojiPaletteController(
            context = context,
            engine = listener.getEngine(),
            isDarkThemeSupplier = { listener.isDarkTheme() },
            keyboardHeightPxSupplier = { listener.getKeyboardHeightPx() },
            onEmojiSelected = { emoji ->
                listener.triggerKeyFeedback()
                listener.onEmojiSelected(emoji)
            },
            onCloseRequested = {
                listener.triggerKeyFeedback()
                emojiPaletteController.hide(mainKeyboardContainer)
            }
        )
        emojiPaletteContainer = emojiPaletteController.createView()
        emojiPaletteContainer.visibility = GONE
        addView(emojiPaletteContainer)

        updateKeyboardLayoutVisibility()
    }

    private fun createNewCandidateButton(
        parent: ViewGroup,
        index: Int,
        padH: Int,
        padV: Int,
        minHeightPx: Int
    ): Button {
        val btn = Button(ContextThemeWrapper(context, R.style.Theme_Karukan)).apply {
            textSize = 15f
            isAllCaps = false
            minHeight = minHeightPx
            minimumHeight = minHeightPx
            minWidth = 0
            minimumWidth = 0
            setPadding(padH, padV, padH, padV)
        }
        val btnMarginPx = dpToPx(3f)
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(btnMarginPx, btnMarginPx, btnMarginPx, btnMarginPx)
        }
        if (index < parent.childCount) {
            parent.addView(btn, index, params)
        } else {
            parent.addView(btn, params)
        }
        return btn
    }

    private fun getCandidateButtonDrawable(isSelected: Boolean, isDark: Boolean, cornerRadiusDp: Float): Drawable {
        val palette = KeyboardColors.get(isDark)
        val colors = if (isDark) {
            ThemeColors(
                keyboardBg = Color.parseColor("#202124"),
                candidateBg = Color.parseColor("#303134"),
                textColor = Color.WHITE,
                candidateBtnBg = Color.parseColor("#3C4043")
            )
        } else {
            ThemeColors(
                keyboardBg = Color.parseColor("#ECEFF1"),
                candidateBg = Color.parseColor("#F8F9FA"),
                textColor = Color.parseColor("#202124"),
                candidateBtnBg = Color.WHITE
            )
        }
        val normalColor = if (isSelected) palette.activeKeyBg else colors.candidateBtnBg
        val pressedColor = if (isSelected) palette.activeKeyPressedBg else if (isDark) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0")
        return createKeyButtonDrawable(
            context = context,
            normalColor = normalColor,
            pressedColor = pressedColor,
            disabledColor = palette.disabledBg,
            cornerRadiusDp = cornerRadiusDp,
            insetDp = 0f
        )
    }

    private fun bindCandidateButton(
        parent: ViewGroup,
        index: Int,
        cand: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): Button {
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        val colors = getThemeColors()
        val isDark = listener?.isDarkTheme() ?: true
        val palette = KeyboardColors.get(isDark)

        val textColor = if (isSelected) palette.activeKeyText else colors.textColor

        val btn = if (index < parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is Button) {
                child.apply { visibility = VISIBLE }
            } else {
                parent.removeViewAt(index)
                createNewCandidateButton(parent, index, padH, padV, (36 * density).toInt())
            }
        } else {
            createNewCandidateButton(parent, index, padH, padV, (36 * density).toInt())
        }

        if (btn.text != cand) {
            btn.text = cand
        }

        val btnTag = Triple(cand, isSelected, isDark)
        if (btn.tag != btnTag) {
            btn.tag = btnTag
            btn.background = getCandidateButtonDrawable(isSelected, isDark, 8f)
            btn.setTextColor(textColor)
            btn.setOnClickListener {
                listener?.triggerKeyFeedback()
                onClick()
            }
        }

        return btn
    }

    private fun updateCandidateLayoutContainer(
        container: ViewGroup?,
        hiragana: String,
        allCandidates: List<String>,
        selectedIndex: Int
    ) {
        if (container == null) return

        for ((index, cand) in allCandidates.withIndex()) {
            val isSelected = (index == selectedIndex)
            bindCandidateButton(container, index, cand, isSelected) {
                listener?.onCandidateSelected(hiragana, cand, index)
            }
        }

        if (container.childCount > allCandidates.size) {
            container.removeViews(allCandidates.size, container.childCount - allCandidates.size)
        }
    }

    fun renderCandidates(
        hiragana: String,
        allCandidates: List<String>,
        selectedIndex: Int
    ) {
        lastHiragana = hiragana
        lastCandidates = allCandidates
        lastSelectedIndex = selectedIndex

        if (allCandidates.isNotEmpty()) {
            if (::expandButton.isInitialized) {
                expandButton.visibility = VISIBLE
            }
        } else {
            if (::expandButton.isInitialized) {
                expandButton.visibility = GONE
            }
        }

        if (::candidateBarContainer.isInitialized) {
            candidateBarContainer.visibility = VISIBLE
        }

        // 1. メインの1行候補バー (常に表示されるため最優先で更新)
        updateCandidateLayoutContainer(candidateLayout, hiragana, allCandidates, selectedIndex)

        // 2. フローティング候補 (ハードキーボード接続時かつ表示対象の時のみ更新)
        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        if (isHardKb && floatingCandidateFlowLayout != null) {
            updateCandidateLayoutContainer(floatingCandidateFlowLayout, hiragana, allCandidates, selectedIndex)
        } else {
            floatingCandidateFlowLayout?.removeAllViews()
        }

        // 3. 複数行展開パネル候補 (isCandidateExpanded == true の時のみLazy描画)
        if (isCandidateExpanded && ::candidateFlowLayout.isInitialized) {
            updateCandidateLayoutContainer(candidateFlowLayout, hiragana, allCandidates, selectedIndex)
        }

        updateFloatingOverlay(selectedIndex)
    }

    fun clearCandidateBar(isEnglishMode: Boolean) {
        lastHiragana = ""
        lastCandidates = emptyList()
        lastSelectedIndex = -1

        candidateLayout.removeAllViews()
        floatingCandidateFlowLayout?.removeAllViews()
        dismissFloatingCandidates()
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = GONE
        }
        if (isCandidateExpanded) {
            collapseCandidates()
        }
        val colors = getThemeColors()
        val defaultCandidateTextView = TextView(context).apply {
            text = if (isEnglishMode) "英数字直接入力" else "Karukan IME 準備完了"
            textSize = 16f
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            setTextColor(colors.textColor)
        }
        candidateLayout.addView(defaultCandidateTextView)
    }

    fun updateInputMode(inputMode: InputMode) {
        this.inputModeState = inputMode
        keyboardLayoutBuilder?.updateInputMode(inputMode)
        if (keyboardLayoutSetting == "niko") {
            val builder = keyboardLayoutBuilder ?: return
            val index = indexOfChild(mainKeyboardContainer)
            if (index != -1) {
                removeView(mainKeyboardContainer)
                mainKeyboardContainer = builder.createNikoKeyboardContainer()
                addView(mainKeyboardContainer, index, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 0)
                })
            }
        }
        val modeText = when (inputMode) {
            InputMode.HIRAGANA -> "かなモード"
            InputMode.ALPHANUMERIC -> "英数モード"
            InputMode.NUMERIC -> "数字モード"
        }
        if (::composingTextView.isInitialized) {
            composingTextView.text = modeText
        }
        clearCandidateBar(inputMode != InputMode.HIRAGANA)
    }

    fun updateLanguageMode(isEnglishMode: Boolean) {
        updateInputMode(if (isEnglishMode) InputMode.ALPHANUMERIC else InputMode.HIRAGANA)
    }

    fun toggleCandidateExpand() {
        if (isCandidateExpanded) {
            collapseCandidates()
        } else {
            expandCandidates()
        }
    }

    fun expandCandidates() {
        if (isCandidateExpanded) return
        isCandidateExpanded = true

        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            emojiPaletteController.hide(mainKeyboardContainer)
        }

        val kbHeight = listener?.getKeyboardHeightPx() ?: 0
        if (kbHeight > 0 && ::candidateExpandedContainer.isInitialized) {
            val params = candidateExpandedContainer.layoutParams
            params.height = kbHeight
            candidateExpandedContainer.layoutParams = params
        }

        if (::mainKeyboardContainer.isInitialized) {
            mainKeyboardContainer.visibility = GONE
        }
        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.visibility = VISIBLE
        }
        if (::expandButton.isInitialized) {
            expandButton.text = "▲"
        }
        if (::expandedScrollView.isInitialized) {
            expandedScrollView.scrollTo(0, 0)
        }

        // 展開パネルが開いたタイミングで最新候補を描画
        if (::candidateFlowLayout.isInitialized) {
            updateCandidateLayoutContainer(candidateFlowLayout, lastHiragana, lastCandidates, lastSelectedIndex)
        }
    }

    fun collapseCandidates() {
        if (!isCandidateExpanded) return
        isCandidateExpanded = false

        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.visibility = GONE
        }
        updateKeyboardLayoutVisibility()
        if (::expandButton.isInitialized) {
            expandButton.text = "▼"
        }
    }

    fun toggleEmojiPalette() {
        collapseCandidates()
        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            emojiPaletteController.toggle(mainKeyboardContainer, symbolMode = (inputModeState != InputMode.HIRAGANA))
        }
    }

    fun toggleSymbolPalette() {
        collapseCandidates()
        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            emojiPaletteController.toggle(mainKeyboardContainer, symbolMode = true)
        }
    }

    fun updateKeyboardLayoutVisibility() {
        if (!::mainKeyboardContainer.isInitialized) return
        if (isCandidateExpanded) {
            mainKeyboardContainer.visibility = GONE
            return
        }
        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        if (isHardKb) {
            mainKeyboardContainer.visibility = GONE
            if (::candidateBarContainer.isInitialized) {
                candidateBarContainer.visibility = VISIBLE
            }
        } else {
            mainKeyboardContainer.visibility = VISIBLE
            if (::candidateBarContainer.isInitialized) {
                candidateBarContainer.visibility = VISIBLE
            }
            dismissFloatingCandidates()
        }
    }

    // --- フローティングオーバーレイウィンドウ関係 ---

    private fun createFloatingPopupWindow(): PopupWindow {
        val colors = getThemeColors()
        val density = resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(colors.candidateBg)
                cornerRadius = 8 * density
                setStroke(dpToPx(1f), if (listener?.isDarkTheme() == true) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0"))
            }
            elevation = dpToPx(8f).toFloat()
        }

        val statusTextView = TextView(context).apply {
            textSize = 14f
            setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
            setTextColor(colors.textColor)
            visibility = GONE
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            visibility = GONE
        }

        val flowLayout = CandidateFlowLayout(context).apply {
            horizontalGapPx = dpToPx(4f)
            verticalGapPx = dpToPx(4f)
        }

        scrollView.addView(
            flowLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        container.addView(statusTextView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        container.addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        floatingStatusTextView = statusTextView
        floatingCandidateScrollView = scrollView
        floatingCandidateFlowLayout = flowLayout

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

    private fun showPopupAtScreenLocationWithSize(
        popup: PopupWindow,
        screenX: Int,
        screenY: Int,
        width: Int,
        height: Int
    ) {
        val anchor = if (::composingTextView.isInitialized && composingTextView.isShown) {
            composingTextView
        } else {
            rootView ?: return
        }

        if (anchor.windowToken == null || !anchor.isAttachedToWindow) {
            if (popup.isShowing) {
                try { popup.dismiss() } catch (e: Exception) {}
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
            if (::candidateBarContainer.isInitialized) {
                candidateBarContainer.visibility = VISIBLE
            }
        }
    }

    fun updateFloatingOverlay(selectedIndex: Int = -1) {
        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        if (!isHardKb) {
            dismissFloatingCandidates()
            return
        }

        val statusText = if (::composingTextView.isInitialized) composingTextView.text.toString() else ""
        val isDefaultEmptyStatus = statusText.isEmpty() ||
                statusText == "かな: (なし)" ||
                statusText.startsWith("かな: (なし)") ||
                statusText == "英数モード"

        val hasComposing = !isDefaultEmptyStatus

        if (!hasComposing && selectedIndex < 0) {
            dismissFloatingCandidates()
            return
        }

        if (floatingPopupWindow == null) {
            floatingPopupWindow = createFloatingPopupWindow()
        }

        val statusView = floatingStatusTextView ?: return
        val scrollView = floatingCandidateScrollView ?: return

        statusView.text = statusText
        statusView.visibility = VISIBLE

        val hasCandidates = (floatingCandidateFlowLayout?.childCount ?: 0) > 0
        val isSelectingCandidates = (selectedIndex >= 0) && hasCandidates
        scrollView.visibility = if (isSelectingCandidates) VISIBLE else GONE

        showFloatingOverlayInternal(isSelectingCandidates)
    }

    private fun showFloatingOverlayInternal(
        isSelectingCandidates: Boolean,
        isCursorAnchorValid: Boolean = this.isCursorAnchorValid,
        cursorX: Float = this.cursorX,
        cursorYBottom: Float = this.cursorYBottom,
        cursorYTop: Float = this.cursorYTop
    ) {
        val popup = floatingPopupWindow ?: return
        val contentView = popup.contentView ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val maxPopupHeight = (screenHeight * 0.35f).toInt()

        val targetWidth = if (isSelectingCandidates) {
            screenWidth - dpToPx(32f)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }

        val statusText = floatingStatusTextView?.text?.toString() ?: ""
        val stateChanged = lastFloatingStatusText != statusText || lastFloatingSelecting != isSelectingCandidates

        if (stateChanged || contentView.measuredWidth == 0) {
            if (targetWidth == ViewGroup.LayoutParams.WRAP_CONTENT) {
                contentView.measure(
                    MeasureSpec.makeMeasureSpec(screenWidth - dpToPx(32f), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(maxPopupHeight, MeasureSpec.AT_MOST)
                )
            } else {
                contentView.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(maxPopupHeight, MeasureSpec.AT_MOST)
                )
            }
            lastFloatingStatusText = statusText
            lastFloatingSelecting = isSelectingCandidates
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
            val loc = IntArray(2)
            if (::composingTextView.isInitialized && composingTextView.isShown) {
                composingTextView.getLocationOnScreen(loc)
                screenY = loc[1] - popupHeight - dpToPx(8f)
            } else {
                screenY = screenHeight - popupHeight - dpToPx(100f)
            }
        }

        screenY = maxOf(0, screenY)

        if (popup.isShowing &&
            lastFloatingX == screenX &&
            lastFloatingY == screenY &&
            lastFloatingWidth == popupWidth &&
            lastFloatingHeight == popupHeight
        ) {
            return
        }

        lastFloatingX = screenX
        lastFloatingY = screenY
        lastFloatingWidth = popupWidth
        lastFloatingHeight = popupHeight

        showPopupAtScreenLocationWithSize(popup, screenX, screenY, popupWidth, popupHeight)
    }

    fun showFloatingCandidates() {
        updateFloatingOverlay(-1)
    }

    fun updateFloatingCandidatePosition() {
        updateFloatingOverlay(-1)
    }

    fun dismissFloatingCandidates() {
        floatingPopupWindow?.dismiss()
        lastFloatingX = Int.MIN_VALUE
        lastFloatingY = Int.MIN_VALUE
        lastFloatingWidth = Int.MIN_VALUE
        lastFloatingHeight = Int.MIN_VALUE
        lastFloatingStatusText = ""
        lastFloatingSelecting = false
    }
}
