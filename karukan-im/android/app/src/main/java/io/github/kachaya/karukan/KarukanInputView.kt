package io.github.kachaya.karukan

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children

/**
 * IME の全体ビュー階層（ステータスバー、1行候補バー、折り返し表示領域、キーボードコンテナ）を管理するカスタム View
 */
class KarukanInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------------------------------------------
    // Region: Theme & Listener Interoperability
    // ---------------------------------------------------------------------------------------------

    interface Listener : KeyboardLayoutBuilder.KeyboardEventListener {
        fun isDarkTheme(): Boolean
        fun getKeyboardHeightPx(): Int
        fun getSettings(): KarukanSettings
        fun isHardKeyboardConnected(): Boolean
        fun isIntegratedHardKeyboardConnected(): Boolean
        fun getEngine(): KarukanEngine
        fun onCandidateSelected(hiragana: String, candidate: String, index: Int)
        fun openSettingsActivity()
        fun commitEmojiDirectly(emoji: String)
    }

    var listener: Listener? = null
    var keyboardLayoutSetting: String = "qwerty"

    private var inputModeState: InputMode = InputMode.HIRAGANA

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    fun getThemeColors(): ThemeColors {
        return ThemeColors.from(context, listener?.isDarkTheme() == true)
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Child View Declarations & Layout Construction
    // ---------------------------------------------------------------------------------------------

    lateinit var composingTextView: TextView
        private set

    private lateinit var topBarContainer: FrameLayout
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var candidateBarContainer: LinearLayout
    private lateinit var candidateBarContentLayout: LinearLayout
    private lateinit var candidateScrollView: HorizontalScrollView
    private lateinit var expandButton: Button
    var isCandidateExpanded: Boolean = false
        private set

    private var lastHiragana: String = ""
    private var lastCandidates: List<String> = emptyList()
    private var lastSelectedIndex: Int = -1

    private lateinit var candidateExpandedContainer: LinearLayout
    private lateinit var candidateFlowLayout: CandidateFlowLayout
    private lateinit var expandedScrollView: ScrollView

    lateinit var mainKeyboardContainer: View
        private set
    private var keyboardLayoutBuilder: KeyboardLayoutBuilder? = null

    var isEmojiPaletteVisible: Boolean = false
        private set
    private var emojiPaletteController: EmojiPaletteController? = null
    private var emojiPaletteContainer: View? = null
    private var emojiHeaderView: View? = null

    // フローティングオーバーレイ (物理キーボード接続時用)
    private val floatingOverlay by lazy {
        FloatingCandidateOverlay(
            context = context,
            dpToPx = { dp -> dpToPx(dp) },
            getThemeColors = { getThemeColors() },
            getAnchorView = { if (::composingTextView.isInitialized && composingTextView.isShown) composingTextView else rootView },
            getInputView = { this },
            isHardKeyboardConnected = { listener?.isHardKeyboardConnected() == true },
            isShowFloatingWindowEnabled = { listener?.getSettings()?.isShowFloatingCandidateWindowEnabled == true },
            isShowFloatingWindowOnSoftKeyboardEnabled = { listener?.getSettings()?.isShowFloatingCandidateWindowOnSoftKeyboardEnabled == true }
        )
    }

    private val floatingTooltipOverlay by lazy {
        FloatingTooltipOverlay(
            context = context,
            dpToPx = { dp -> dpToPx(dp) },
            getAnchorView = { if (::composingTextView.isInitialized && composingTextView.isShown) composingTextView else rootView },
            getInputView = { this },
            isDarkSupplier = { listener?.isDarkTheme() == true }
        )
    }

    var isCursorAnchorValid: Boolean = false
        private set
    var cursorX: Float = Float.NaN
        private set
    var cursorYBottom: Float = Float.NaN
        private set
    var cursorYTop: Float = Float.NaN
        private set

    companion object {
        private const val TAG = "KarukanInputView"
    }

    fun updateCursorPosition(isValid: Boolean, x: Float, yBottom: Float, yTop: Float) {
        this.isCursorAnchorValid = isValid
        this.cursorX = x
        this.cursorYBottom = yBottom
        this.cursorYTop = yTop
    }

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        setPadding(0, 0, 0, 0)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        buildViewHierarchy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let { insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            setPadding(0, 0, 0, navBars.bottom)
        }
        ViewCompat.requestApplyInsets(this)
        post {
            ViewCompat.requestApplyInsets(this)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissFloatingCandidates()
    }

    private fun updateThemeColors() {
        val colors = getThemeColors()
        setBackgroundColor(colors.bg)
        if (::composingTextView.isInitialized) {
            composingTextView.setBackgroundColor(colors.bg)
            composingTextView.setTextColor(colors.statusText)
        }
        if (::toolbarContainer.isInitialized) {
            toolbarContainer.setBackgroundColor(colors.toolbarBg)
            for (child in toolbarContainer.children) {
                if (child is TextView) {
                    child.setTextColor(colors.candidateText)
                }
            }
        }
        if (::candidateBarContainer.isInitialized) {
            candidateBarContainer.setBackgroundColor(colors.bg)
        }
        if (::expandButton.isInitialized) {
            expandButton.background = createKeyDrawableByType(context, colors.isDark, KeyBackgroundType.ACTION, cornerRadiusDp = 6f, insetDp = 3f)
            expandButton.setTextColor(colors.keyText)
        }
        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.setBackgroundColor(colors.bg)
        }
    }

    private fun updateToolbarContent() {
        if (!::toolbarContainer.isInitialized) return
        toolbarContainer.removeAllViews()
        val colors = getThemeColors()

        val btnSettings = TextView(context).apply {
            text = "⚙️ 設定"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.candidateText)
            setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                listener?.openSettingsActivity()
            }
        }
        toolbarContainer.addView(btnSettings)

        if (keyboardLayoutSetting == "graffiti") {
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f)
            }
            toolbarContainer.addView(spacer)

            val btnHelp = TextView(context).apply {
                text = "🖊 ヘルプ"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(colors.candidateText)
                setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    GraffitiHelpDialog.show(this, listener?.isDarkTheme() == true)
                }
            }
            toolbarContainer.addView(btnHelp)
        }
    }

    private fun buildViewHierarchy() {
        val colors = getThemeColors()
        updateThemeColors()

        // 1. ステータス領域 (モード・未確定文字列表示)
        composingTextView = TextView(context).apply {
            textSize = 14f
            setPadding(dpToPx(12f), dpToPx(4f), dpToPx(12f), dpToPx(4f))
            setTextColor(colors.statusText)
            setBackgroundColor(colors.bg)
            visibility = GONE
        }

        // 2. ツールバー / 1行変換候補バー 重ね合わせスロット容器
        val slotHeight = getKeyHeight()
        topBarContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, slotHeight)
        }

        // 2a. ツールバー領域 (未入力・非変換時に表示)
        toolbarContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
            setBackgroundColor(colors.toolbarBg)
        }

        updateToolbarContent()

        // 2b. 1行変換候補バー領域 (文字入力中・変換候補存在時に表示)
        candidateBarContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
            setBackgroundColor(colors.bg)
        }

        candidateScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }

        candidateBarContentLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        candidateScrollView.addView(
            candidateBarContentLayout,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )

        expandButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = "▼"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.keyText)
            background = createKeyDrawableByType(context, colors.isDark, KeyBackgroundType.ACTION, cornerRadiusDp = 6f, insetDp = 3f)
            setPadding(0, 0, 0, 0)
            visibility = GONE
            setOnClickListener {
                toggleCandidateExpand()
            }
        }

        candidateBarContainer.addView(
            candidateScrollView,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f)
        )
        candidateBarContainer.addView(
            expandButton,
            LayoutParams(slotHeight, LayoutParams.MATCH_PARENT)
        )

        // 3. 複数行候補展開領域
        candidateExpandedContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            setBackgroundColor(colors.bg)
        }

        expandedScrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
        }

        candidateFlowLayout = CandidateFlowLayout(context).apply {
            horizontalGapPx = dpToPx(6f)
            verticalGapPx = dpToPx(6f)
            setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
        }

        expandedScrollView.addView(
            candidateFlowLayout,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        candidateExpandedContainer.addView(
            expandedScrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        addView(composingTextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(topBarContainer, LayoutParams(LayoutParams.MATCH_PARENT, getKeyHeight()))
        addView(candidateExpandedContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        updateTopBarContent()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Keyboard Container & Layout Swapping
    // ---------------------------------------------------------------------------------------------

    fun getKeyHeight(): Int {
        val activeListener = listener ?: return dpToPx(52f)
        val totalH = activeListener.getKeyboardHeightPx()
        val provider = KeyboardLayoutRegistry.getProvider(keyboardLayoutSetting)
        return totalH / provider.rowCount
    }

    private fun updateTopBarHeight() {
        if (!::topBarContainer.isInitialized) return
        val kh = getKeyHeight()
        val lp = topBarContainer.layoutParams
        if (lp != null && lp.height != kh) {
            lp.height = kh
            topBarContainer.layoutParams = lp
        }
    }

    /**
     * トップバーの表示状態を一括決定・更新する一元化メソッド。
     * - 絵文字パレット表示中 → 絵文字カテゴリーバー
     * - 文字入力キーボード表示中 & 入力中 (インライン候補あり) → 1行変換候補バー
     * - 文字入力キーボード表示中 & 未入力状態 (またはフローティング候補時) → 設定ツールバー
     */
    fun updateTopBarContent() {
        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        val showSoftFloating = listener?.getSettings()?.isShowFloatingCandidateWindowOnSoftKeyboardEnabled == true
        val useFloating = isHardKb || (!isHardKb && showSoftFloating)

        if (isEmojiPaletteVisible && emojiHeaderView != null) {
            showEmojiHeaderInternal()
        } else if (!useFloating && lastCandidates.isNotEmpty()) {
            showCandidateBarInternal()
        } else {
            showToolbarInternal()
        }
    }

    private fun getOrCreateEmojiPaletteContainer(): View {
        if (emojiPaletteContainer != null) return emojiPaletteContainer!!
        val activeListener = listener
        val activeEngine = activeListener?.getEngine() ?: KarukanEngine(context)
        val controller = EmojiPaletteController(
            context = context,
            engine = activeEngine,
            isDarkThemeSupplier = { activeListener?.isDarkTheme() == true },
            keyboardHeightPxSupplier = { activeListener?.getKeyboardHeightPx() ?: dpToPx(260f) },
            keyButtonHeightSupplier = { (activeListener?.getKeyboardHeightPx() ?: dpToPx(260f)) / 5 },
            bottomActionSpecs = listOf(
                KeySpec("×", KeyType.NORMAL),
                KeySpec("←", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("↓", KeyType.DPAD_DOWN, repeatable = true),
                KeySpec("↑", KeyType.DPAD_UP, repeatable = true),
                KeySpec("→", KeyType.DPAD_RIGHT, repeatable = true),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true),
                KeySpec("", KeyType.ENTER, R.drawable.outline_send_24)
            ),
            createKeyButton = { spec ->
                val isDark = activeListener?.isDarkTheme() == true
                val rowHeight = (activeListener?.getKeyboardHeightPx() ?: dpToPx(260f)) / 5
                KeyButton(
                    context = context,
                    spec = spec,
                    keyHeight = rowHeight,
                    keyTextColor = getThemeColors().keyText,
                    isDark = isDark,
                    onFeedback = { activeListener?.onKeyFeedback() }
                ).apply {
                    updateBackgroundType(KeyBackgroundType.ACTION, isDark)
                }
            },
            setupRepeatableTouchListener = { btn ->
                btn.setOnClickListener {
                    when (btn.currentType) {
                        KeyType.NORMAL -> toggleEmojiPalette()
                        KeyType.DPAD_LEFT -> activeListener?.onDpadLeft()
                        KeyType.DPAD_DOWN -> activeListener?.onDpadDown()
                        KeyType.DPAD_UP -> activeListener?.onDpadUp()
                        KeyType.DPAD_RIGHT -> activeListener?.onDpadRight()
                        KeyType.BACKSPACE -> activeListener?.onBackspace()
                        KeyType.ENTER -> commitSelectedEmoji()
                        else -> {}
                    }
                }
            },
            onEmojiSelected = { emoji -> activeListener?.commitEmojiDirectly(emoji) },
            onCloseRequested = { toggleEmojiPalette() },
            onLeft = { activeListener?.onDpadLeft() },
            onDown = { activeListener?.onDpadDown() },
            onUp = { activeListener?.onDpadUp() },
            onRight = { activeListener?.onDpadRight() },
            onBackspace = { activeListener?.onBackspace() },
            onEnter = { commitSelectedEmoji() },
            onFeedback = { activeListener?.onKeyFeedback() }
        )
        this.emojiPaletteController = controller
        val containerView = controller.createView()
        val headerView = controller.createHeaderView()
        this.emojiHeaderView = headerView
        this.emojiPaletteContainer = containerView
        return containerView
    }

    private fun showToolbarInternal() {
        updateThemeColors()
        updateTopBarHeight()
        topBarContainer.removeAllViews()
        if (::toolbarContainer.isInitialized) {
            topBarContainer.addView(
                toolbarContainer,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = GONE
        }
        requestLayout()
    }

    private fun showCandidateBarInternal() {
        updateThemeColors()
        updateTopBarHeight()
        topBarContainer.removeAllViews()
        if (::candidateBarContainer.isInitialized) {
            topBarContainer.addView(
                candidateBarContainer,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = VISIBLE
        }
        requestLayout()
    }

    private fun showEmojiHeaderInternal() {
        updateThemeColors()
        updateTopBarHeight()
        topBarContainer.removeAllViews()
        if (emojiHeaderView != null) {
            (emojiHeaderView!!.parent as? ViewGroup)?.removeView(emojiHeaderView)
            topBarContainer.addView(
                emojiHeaderView!!,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = GONE
        }
        requestLayout()
    }

    fun onEmojiHeaderCreated(headerView: View) {
        emojiHeaderView = headerView
        updateTopBarContent()
    }

    fun setupKeyboardLayout(layoutName: String, forceRebuild: Boolean = false) {
        if (!forceRebuild && ::mainKeyboardContainer.isInitialized && keyboardLayoutSetting == layoutName) {
            updateThemeColors()
            updateToolbarContent()
            updateTopBarContent()
            return
        }
        this.keyboardLayoutSetting = layoutName
        updateThemeColors()
        updateToolbarContent()

        val activeListener = listener ?: return
        val builder = KeyboardLayoutBuilder(
            context = context,
            isDarkThemeSupplier = { activeListener.isDarkTheme() },
            keyButtonHeightSupplier = {
                val totalH = activeListener.getKeyboardHeightPx()
                val provider = KeyboardLayoutRegistry.getProvider(layoutName)
                totalH / provider.rowCount
            },
            keyboardHeightPxSupplier = { activeListener.getKeyboardHeightPx() },
            isEnglishModeSupplier = { inputModeState == InputMode.ALPHANUMERIC },
            inputModeSupplier = { inputModeState },
            listener = activeListener
        ).apply {
            onContainerSwapRequested = { newContainer ->
                swapKeyboardContainer(newContainer)
            }
        }
        this.keyboardLayoutBuilder = builder

        val keyboardView = builder.createContainerForLayout(layoutName)
        swapKeyboardContainer(keyboardView)
        updateTopBarHeight()
        requestLayout()
        invalidate()
    }

    fun refreshKeyboard() {
        setupKeyboardLayout(keyboardLayoutSetting, forceRebuild = true)
    }

    private fun swapKeyboardContainer(newContainer: View) {
        if (::mainKeyboardContainer.isInitialized) {
            removeView(mainKeyboardContainer)
        }
        mainKeyboardContainer = newContainer
        val lp = newContainer.layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(newContainer, lp)
        updateKeyboardLayoutVisibility()
        requestLayout()
        invalidate()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Candidate View Rendering & Binding
    // ---------------------------------------------------------------------------------------------

    fun updateCandidates(hiragana: String, candidates: List<String>, selectedIndex: Int = -1) {
        lastHiragana = hiragana
        lastCandidates = candidates
        lastSelectedIndex = selectedIndex

        val colors = getThemeColors()
        val showAnnotations = listener?.getSettings()?.isShowCandidateAnnotationsEnabled ?: true
        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        val showSoftFloating = listener?.getSettings()?.isShowFloatingCandidateWindowOnSoftKeyboardEnabled == true
        val useFloating = isHardKb || (!isHardKb && showSoftFloating)

        if (hiragana.isEmpty() || candidates.isEmpty()) {
            collapseCandidates()
            candidateBarContentLayout.removeAllViews()
            if (::candidateScrollView.isInitialized) {
                candidateScrollView.scrollTo(0, 0)
            }
            if (::candidateFlowLayout.isInitialized) {
                candidateFlowLayout.removeAllViews()
            }
            if (floatingOverlay.candidateFlowLayout != null) {
                floatingOverlay.candidateFlowLayout?.removeAllViews()
            }
            updateTopBarContent()
            updateFloatingOverlay(selectedIndex)
            return
        }

        updateTopBarContent()

        if (useFloating) {
            val floatingFlow = floatingOverlay.getOrCreateCandidateFlowLayout()
            CandidateRenderer.render(
                container = floatingFlow,
                candidates = candidates,
                selectedIndex = selectedIndex,
                colors = colors,
                showAnnotations = showAnnotations,
                isSingleRowBar = false,
                dpToPx = { dp -> dpToPx(dp) },
                onItemClick = { i, rawCandidate ->
                    collapseCandidates()
                    val cleanCand = stripCandidateAnnotations(rawCandidate)
                    listener?.onCandidateSelected(hiragana, cleanCand, i)
                }
            )
            if (selectedIndex >= 0) {
                floatingOverlay.scrollToSelectedCandidate(selectedIndex)
            }
        } else {
            CandidateRenderer.render(
                container = candidateBarContentLayout,
                candidates = candidates,
                selectedIndex = selectedIndex,
                colors = colors,
                showAnnotations = showAnnotations,
                isSingleRowBar = true,
                dpToPx = { dp -> dpToPx(dp) },
                onItemClick = { i, rawCandidate ->
                    collapseCandidates()
                    val cleanCand = stripCandidateAnnotations(rawCandidate)
                    listener?.onCandidateSelected(hiragana, cleanCand, i)
                }
            )

            if (::candidateFlowLayout.isInitialized) {
                CandidateRenderer.render(
                    container = candidateFlowLayout,
                    candidates = candidates,
                    selectedIndex = selectedIndex,
                    colors = colors,
                    showAnnotations = showAnnotations,
                    isSingleRowBar = false,
                    dpToPx = { dp -> dpToPx(dp) },
                    onItemClick = { i, rawCandidate ->
                        collapseCandidates()
                        val cleanCand = stripCandidateAnnotations(rawCandidate)
                        listener?.onCandidateSelected(hiragana, cleanCand, i)
                    }
                )
                if (isCandidateExpanded && ::expandedScrollView.isInitialized) {
                    scrollToSelectedCandidateVertical(expandedScrollView, candidateFlowLayout, selectedIndex)
                }
            }

            if (!isCandidateExpanded) {
                if (selectedIndex >= 0) {
                    scrollToSelectedCandidate(selectedIndex)
                } else if (::candidateScrollView.isInitialized) {
                    candidateScrollView.scrollTo(0, 0)
                }
            }
        }

        updateFloatingOverlay(selectedIndex)
    }

    private fun updateCandidateLayoutContainer(
        container: ViewGroup,
        hiragana: String,
        candidates: List<String>,
        selectedIndex: Int
    ) {
        val colors = getThemeColors()
        val showAnnotations = listener?.getSettings()?.isShowCandidateAnnotationsEnabled ?: true
        CandidateRenderer.render(
            container = container,
            candidates = candidates,
            selectedIndex = selectedIndex,
            colors = colors,
            showAnnotations = showAnnotations,
            isSingleRowBar = false,
            dpToPx = { dp -> dpToPx(dp) },
            onItemClick = { i, rawCandidate ->
                collapseCandidates()
                val cleanCand = stripCandidateAnnotations(rawCandidate)
                listener?.onCandidateSelected(hiragana, cleanCand, i)
            }
        )
    }

    private fun stripCandidateAnnotations(raw: String): String {
        return raw.extractPureCandidate()
    }

    private fun scrollToSelectedCandidate(index: Int) {
        if (index < 0 || index >= candidateBarContentLayout.childCount) return
        val selectedView = candidateBarContentLayout.getChildAt(index) ?: return
        candidateScrollView.post {
            val scrollTo = selectedView.left - (candidateScrollView.width / 2) + (selectedView.width / 2)
            candidateScrollView.smoothScrollTo(maxOf(0, scrollTo), 0)
        }
    }

    private fun scrollToSelectedCandidateVertical(scrollView: ScrollView, flowLayout: CandidateFlowLayout, index: Int) {
        if (index < 0 || index >= flowLayout.childCount) return
        val selectedView = flowLayout.getChildAt(index) ?: return
        scrollView.post {
            val scrollTo = selectedView.top - (scrollView.height / 2) + (selectedView.height / 2)
            scrollView.smoothScrollTo(0, maxOf(0, scrollTo))
        }
    }

    fun clearCandidateBar() {
        collapseCandidates()
        lastHiragana = ""
        lastCandidates = emptyList()
        lastSelectedIndex = -1
        updateTopBarContent()
        candidateBarContentLayout.removeAllViews()
        if (::candidateScrollView.isInitialized) {
            candidateScrollView.scrollTo(0, 0)
        }
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }
        floatingOverlay.candidateFlowLayout?.removeAllViews()
        dismissFloatingCandidates()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Mode Management & Panel Expand
    // ---------------------------------------------------------------------------------------------

    fun updateEditorInfo(info: android.view.inputmethod.EditorInfo?) {
        if (::mainKeyboardContainer.isInitialized && mainKeyboardContainer is ViewGroup) {
            (mainKeyboardContainer as ViewGroup).children.forEach { row ->
                if (row is ViewGroup) {
                    row.children.forEach { btn ->
                        if (btn is KeyButton) {
                            btn.updateEditorInfo(info)
                        }
                    }
                }
            }
        }
    }

    fun setInputMode(inputMode: InputMode) {
        this.inputModeState = inputMode
        keyboardLayoutBuilder?.updateInputMode(inputMode)

        if (::mainKeyboardContainer.isInitialized && mainKeyboardContainer is ViewGroup) {
            val isDark = listener?.isDarkTheme() == true
            (mainKeyboardContainer as ViewGroup).children.forEach { row ->
                if (row is ViewGroup) {
                    row.children.forEach { btn ->
                        if (btn is KeyButton) {
                            btn.updateInputMode(inputMode, isDark)
                        }
                    }
                }
            }
        }
        val modeText = when (inputMode) {
            InputMode.HIRAGANA -> "jp"
            InputMode.ALPHANUMERIC -> "en"
            InputMode.NUMERIC -> "num"
            InputMode.SYMBOL -> "sym"
        }
        if (::composingTextView.isInitialized) {
            composingTextView.text = ""
        }
        collapseCandidates()
        if (inputMode != InputMode.HIRAGANA) {
            lastHiragana = ""
            lastCandidates = emptyList()
            lastSelectedIndex = -1
        }
        updateTopBarContent()
        candidateBarContentLayout.removeAllViews()
        if (::candidateScrollView.isInitialized) {
            candidateScrollView.scrollTo(0, 0)
        }
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }
        floatingOverlay.candidateFlowLayout?.removeAllViews()
        val isHardKb = listener?.isHardKeyboardConnected() == true
        val showSoftFloating = listener?.getSettings()?.isShowFloatingCandidateWindowOnSoftKeyboardEnabled == true
        if (isHardKb || showSoftFloating) {
            updateFloatingTooltipOverlay(modeText)
        }
    }

    fun updateNikoInputState(hasInput: Boolean) {
        if (keyboardLayoutSetting == "niko") {
            keyboardLayoutBuilder?.updateNikoInputState(hasInput)
        }
    }

    fun toggleCandidateExpand() {
        if (isCandidateExpanded) {
            collapseCandidates()
        } else {
            expandCandidates()
        }
    }

    fun expandCandidates() {
        if (isCandidateExpanded || lastCandidates.isEmpty()) return
        isCandidateExpanded = true

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

        if (::candidateFlowLayout.isInitialized) {
            updateCandidateLayoutContainer(candidateFlowLayout, lastHiragana, lastCandidates, lastSelectedIndex)
            if (::expandedScrollView.isInitialized) {
                scrollToSelectedCandidateVertical(expandedScrollView, candidateFlowLayout, lastSelectedIndex)
            }
        }
        requestLayout()
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
        requestLayout()
    }

    fun resetKeyboardState() {
        hideEmojiPalette()
        collapseCandidates()
        dismissFloatingCandidates()
        requestLayout()
    }

    fun toggleEmojiPalette() {
        collapseCandidates()
        isEmojiPaletteVisible = !isEmojiPaletteVisible
        updateKeyboardLayoutVisibility()
    }

    fun hideEmojiPalette() {
        if (isEmojiPaletteVisible) {
            isEmojiPaletteVisible = false
            updateKeyboardLayoutVisibility()
        }
    }

    fun selectNextEmojiCategory() {
        emojiPaletteController?.selectNextCategory()
    }

    fun selectPreviousEmojiCategory() {
        emojiPaletteController?.selectPreviousCategory()
    }

    fun moveEmojiSelection(dx: Int, dy: Int) {
        emojiPaletteController?.moveGridSelection(dx, dy)
    }

    fun commitSelectedEmoji() {
        emojiPaletteController?.commitSelectedEmoji()
    }

    fun updateKeyboardLayoutVisibility() {
        if (!::mainKeyboardContainer.isInitialized) return
        if (isCandidateExpanded) {
            mainKeyboardContainer.visibility = GONE
            emojiPaletteContainer?.visibility = GONE
            return
        }

        if (isEmojiPaletteVisible) {
            mainKeyboardContainer.visibility = GONE
            val palette = getOrCreateEmojiPaletteContainer()
            val isHardKb = listener?.isHardKeyboardConnected() == true
            val fullKbHeight = listener?.getKeyboardHeightPx() ?: (if (mainKeyboardContainer.height > 0) mainKeyboardContainer.height else dpToPx(260f))
            val singleRowHeight = fullKbHeight / 5
            val targetHeight = if (isHardKb) singleRowHeight * 4 else fullKbHeight

            var lp = palette.layoutParams
            if (lp == null) {
                lp = LayoutParams(LayoutParams.MATCH_PARENT, targetHeight)
            } else if (lp.height != targetHeight) {
                lp.height = targetHeight
            }
            palette.layoutParams = lp

            if (palette.parent == null) {
                addView(palette, lp)
            }
            palette.visibility = VISIBLE
            emojiPaletteController?.show(
                isHardKeyboard = isHardKb,
                symbolMode = false
            )
        } else {
            emojiPaletteContainer?.visibility = GONE
            val isHardKb = listener?.isHardKeyboardConnected() ?: false
            val isIntegrated = listener?.isIntegratedHardKeyboardConnected() ?: false
            if (isHardKb && !isIntegrated) {
                mainKeyboardContainer.visibility = GONE
            } else {
                mainKeyboardContainer.visibility = VISIBLE
            }
        }

        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        val showSoftFloating = listener?.getSettings()?.isShowFloatingCandidateWindowOnSoftKeyboardEnabled == true
        val useFloating = isHardKb || (!isHardKb && showSoftFloating)
        val isStatusBarEnabled = listener?.getSettings()?.isShowInputStatusBarEnabled ?: true
        val statusVisibility = if (useFloating || !isStatusBarEnabled) GONE else VISIBLE

        if (::composingTextView.isInitialized) {
            composingTextView.visibility = statusVisibility
        }

        updateTopBarContent()

        if (!useFloating) {
            dismissFloatingCandidates()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Floating Overlay Popup Delegation
    // ---------------------------------------------------------------------------------------------

    fun updateFloatingOverlay(selectedIndex: Int = -1) {
        floatingOverlay.updateOverlay(
            selectedIndex = selectedIndex,
            isCursorAnchorValid = isCursorAnchorValid,
            cursorX = cursorX,
            cursorYBottom = cursorYBottom,
            cursorYTop = cursorYTop
        )
    }

    fun updateFloatingTooltipOverlay(message: String, autoDismissDelayMs: Long = 1200L) {
        floatingTooltipOverlay.showTooltip(
            message = message,
            isCursorAnchorValid = isCursorAnchorValid,
            cursorX = cursorX,
            cursorYBottom = cursorYBottom,
            cursorYTop = cursorYTop,
            autoDismissDelayMs = autoDismissDelayMs
        )
    }

    fun dismissFloatingCandidates() {
        floatingOverlay.dismiss()
        floatingTooltipOverlay.dismiss()
    }
}
