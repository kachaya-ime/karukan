package io.github.kachaya.karukan

import android.content.Context
import android.graphics.Color
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
import androidx.core.graphics.ColorUtils
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
    }

    var listener: Listener? = null
    var keyboardLayoutSetting: String = "qwerty"

    private var inputModeState: InputMode = InputMode.HIRAGANA

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    data class ThemeColors(
        val bg: Int,
        val statusText: Int,
        val candidateBg: Int,
        val candidateText: Int,
        val candidateSelectedBg: Int,
        val candidateSelectedText: Int,
        val textColor: Int
    )

    fun getThemeColors(): ThemeColors {
        val isDark = listener?.isDarkTheme() == true
        val baseAccentGreen = Color.parseColor("#00897B")
        val baseDarkBg = Color.parseColor("#202124")
        val baseLightBg = Color.parseColor("#F8F9FA")
        val baseTextLight = Color.parseColor("#E8EAED")
        val baseTextDark = Color.parseColor("#202124")

        val bg = if (isDark) baseDarkBg else baseLightBg
        val statusText = if (isDark) baseTextLight else baseTextDark
        val textColor = if (isDark) Color.WHITE else baseTextDark

        // candidateBg: ベース背景に 4% ティント・ブレンド
        val candidateBg = if (isDark) {
            ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.04f)
        } else {
            ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.04f)
        }
        val candidateText = if (isDark) baseTextLight else baseTextDark

        // candidateSelectedBg: ベース背景にベースグリーンをティント・ブレンド
        val candidateSelectedBg = if (isDark) {
            ColorUtils.blendARGB(bg, baseAccentGreen, 0.30f)
        } else {
            ColorUtils.blendARGB(bg, baseAccentGreen, 0.15f)
        }

        // candidateSelectedText: ベースグリーンとブラック/ホワイトのブレンド
        val candidateSelectedText = if (isDark) {
            Color.WHITE
        } else {
            ColorUtils.blendARGB(baseAccentGreen, Color.BLACK, 0.50f)
        }

        return ThemeColors(
            bg = bg,
            statusText = statusText,
            candidateBg = candidateBg,
            candidateText = candidateText,
            candidateSelectedBg = candidateSelectedBg,
            candidateSelectedText = candidateSelectedText,
            textColor = textColor
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Child View Declarations & Layout Construction
    // ---------------------------------------------------------------------------------------------

    lateinit var composingTextView: TextView
        private set

    private lateinit var candidateSlotContainer: FrameLayout
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
    private lateinit var emojiPaletteContainer: LinearLayout
    private lateinit var emojiPaletteController: EmojiPaletteController

    // フローティングオーバーレイ (物理キーボード接続時用)
    private val floatingOverlay by lazy {
        FloatingCandidateOverlay(
            context = context,
            isDarkSupplier = { listener?.isDarkTheme() == true },
            dpToPx = { dp -> dpToPx(dp) },
            getThemeColors = { getThemeColors() },
            getComposingText = { if (::composingTextView.isInitialized) composingTextView.text.toString() else "" },
            getAnchorView = { if (::composingTextView.isInitialized && composingTextView.isShown) composingTextView else rootView },
            isHardKeyboardConnected = { listener?.isHardKeyboardConnected() == true },
            isShowFloatingWindowEnabled = { listener?.getSettings()?.isShowFloatingCandidateWindowEnabled == true },
            isShowFloatingStatusEnabled = { listener?.getSettings()?.isShowFloatingStatusTextEnabled == true },
            onCandidateSelected = { hiragana, cand, index -> listener?.onCandidateSelected(hiragana, cand, index) }
        )
    }

    val floatingCandidateFlowLayout: CandidateFlowLayout?
        get() = floatingOverlay.candidateFlowLayout

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
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
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

    private fun updateThemeColors() {
        val colors = getThemeColors()
        setBackgroundColor(colors.bg)
        if (::composingTextView.isInitialized) {
            composingTextView.setBackgroundColor(colors.bg)
            composingTextView.setTextColor(colors.statusText)
        }
        if (::toolbarContainer.isInitialized) {
            toolbarContainer.setBackgroundColor(colors.bg)
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
            expandButton.setTextColor(colors.candidateText)
        }
        if (::candidateExpandedContainer.isInitialized) {
            candidateExpandedContainer.setBackgroundColor(colors.bg)
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
        candidateSlotContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, slotHeight)
        }

        // 2a. ツールバー領域 (未入力・非変換時に表示)
        toolbarContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
            setBackgroundColor(colors.bg)
            visibility = VISIBLE
        }

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

        // 2b. 1行変換候補バー領域 (文字入力中・変換候補存在時に表示)
        candidateBarContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4f), 0, dpToPx(4f), 0)
            setBackgroundColor(colors.bg)
            visibility = GONE
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
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(colors.candidateText)
            setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
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
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )

        candidateSlotContainer.addView(
            toolbarContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        candidateSlotContainer.addView(
            candidateBarContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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

        // 4. 絵文字・記号パレットコンテナ
        val activeEngine = listener?.getEngine() ?: KarukanEngine(context)
        emojiPaletteController = EmojiPaletteController(
            context = context,
            engine = activeEngine,
            isDarkThemeSupplier = { listener?.isDarkTheme() == true },
            keyboardHeightPxSupplier = { listener?.getKeyboardHeightPx() ?: dpToPx(240f) },
            onEmojiSelected = { emoji -> listener?.onKeyPress(emoji) },
            onCloseRequested = { hideEmojiPalette() }
        )
        emojiPaletteContainer = emojiPaletteController.createView()

        addView(composingTextView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(candidateSlotContainer, LayoutParams(LayoutParams.MATCH_PARENT, getKeyHeight()))
        addView(candidateExpandedContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(emojiPaletteContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        showToolbar()
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

    private fun updateCandidateSlotHeight() {
        if (!::candidateSlotContainer.isInitialized) return
        val kh = getKeyHeight()
        val lp = candidateSlotContainer.layoutParams
        if (lp != null && lp.height != kh) {
            lp.height = kh
            candidateSlotContainer.layoutParams = lp
        }
    }

    private fun showToolbar() {
        updateThemeColors()
        updateCandidateSlotHeight()
        if (::toolbarContainer.isInitialized) {
            toolbarContainer.visibility = VISIBLE
        }
        if (::candidateBarContainer.isInitialized) {
            candidateBarContainer.visibility = GONE
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = GONE
        }
    }

    private fun showCandidateBar() {
        updateThemeColors()
        updateCandidateSlotHeight()
        if (::toolbarContainer.isInitialized) {
            toolbarContainer.visibility = GONE
        }
        if (::candidateBarContainer.isInitialized) {
            candidateBarContainer.visibility = VISIBLE
        }
        if (::expandButton.isInitialized) {
            expandButton.visibility = VISIBLE
        }
    }

    fun setupKeyboardLayout(layoutName: String) {
        this.keyboardLayoutSetting = layoutName
        updateThemeColors()

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
        updateCandidateSlotHeight()
        requestLayout()
        invalidate()
    }

    private fun swapKeyboardContainer(newContainer: View) {
        if (::mainKeyboardContainer.isInitialized) {
            removeView(mainKeyboardContainer)
        }
        mainKeyboardContainer = newContainer
        addView(newContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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

        if (hiragana.isEmpty() || candidates.isEmpty()) {
            collapseCandidates()
            showToolbar()
            candidateBarContentLayout.removeAllViews()
            if (::candidateFlowLayout.isInitialized) {
                candidateFlowLayout.removeAllViews()
            }
            if (floatingOverlay.candidateFlowLayout != null) {
                floatingOverlay.candidateFlowLayout?.removeAllViews()
            }
            updateFloatingOverlay(selectedIndex)
            return
        }

        if (isHardKb) {
            showToolbar()
            val floatingFlow = floatingOverlay.getOrCreateCandidateFlowLayout()
            renderCandidateList(
                container = floatingFlow,
                candidates = candidates,
                selectedIndex = selectedIndex,
                colors = colors,
                showAnnotations = showAnnotations,
                isSingleRowBar = false,
                hiragana = hiragana
            )
        } else {
            showCandidateBar()

            renderCandidateList(
                container = candidateBarContentLayout,
                candidates = candidates,
                selectedIndex = selectedIndex,
                colors = colors,
                showAnnotations = showAnnotations,
                isSingleRowBar = true,
                hiragana = hiragana
            )

            if (::candidateFlowLayout.isInitialized) {
                renderCandidateList(
                    container = candidateFlowLayout,
                    candidates = candidates,
                    selectedIndex = selectedIndex,
                    colors = colors,
                    showAnnotations = showAnnotations,
                    isSingleRowBar = false,
                    hiragana = hiragana
                )
                if (isCandidateExpanded && ::expandedScrollView.isInitialized) {
                    scrollToSelectedCandidateVertical(expandedScrollView, candidateFlowLayout, selectedIndex)
                }
            }

            if (!isCandidateExpanded && selectedIndex >= 0) {
                scrollToSelectedCandidate(selectedIndex)
            }
        }

        updateFloatingOverlay(selectedIndex)
    }

    private fun renderCandidateList(
        container: ViewGroup,
        candidates: List<String>,
        selectedIndex: Int,
        colors: ThemeColors,
        showAnnotations: Boolean,
        isSingleRowBar: Boolean,
        hiragana: String
    ) {
        val existingCount = container.childCount
        val targetCount = candidates.size

        if (existingCount > targetCount) {
            container.removeViews(targetCount, existingCount - targetCount)
        }

        val paddingH = dpToPx(10f)
        val paddingV = dpToPx(6f)

        for (i in 0 until targetCount) {
            val rawCandidate = candidates[i]
            val isSelected = (i == selectedIndex)

            val displayCandidate = if (showAnnotations) {
                rawCandidate
            } else {
                stripCandidateAnnotations(rawCandidate)
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

            if (isSelected) {
                child.setBackgroundColor(colors.candidateSelectedBg)
                child.setTextColor(colors.candidateSelectedText)
            } else {
                child.setBackgroundColor(colors.candidateBg)
                child.setTextColor(colors.candidateText)
            }

            child.setOnClickListener {
                collapseCandidates()
                val cleanCand = stripCandidateAnnotations(rawCandidate)
                listener?.onCandidateSelected(hiragana, cleanCand, i)
            }
        }
    }

    private fun stripCandidateAnnotations(raw: String): String {
        val idx = raw.indexOf(" (")
        return if (idx > 0) raw.substring(0, idx) else raw
    }

    private fun updateCandidateLayoutContainer(
        container: ViewGroup,
        hiragana: String,
        candidates: List<String>,
        selectedIndex: Int
    ) {
        val colors = getThemeColors()
        val showAnnotations = listener?.getSettings()?.isShowCandidateAnnotationsEnabled ?: true
        renderCandidateList(
            container = container,
            candidates = candidates,
            selectedIndex = selectedIndex,
            colors = colors,
            showAnnotations = showAnnotations,
            isSingleRowBar = false,
            hiragana = hiragana
        )
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

    fun clearCandidateBar(clearCacheText: Boolean = true) {
        collapseCandidates()
        if (clearCacheText) {
            lastHiragana = ""
            lastCandidates = emptyList()
            lastSelectedIndex = -1
        }
        showToolbar()
        candidateBarContentLayout.removeAllViews()
        if (::candidateFlowLayout.isInitialized) {
            candidateFlowLayout.removeAllViews()
        }
        floatingOverlay.candidateFlowLayout?.removeAllViews()
        dismissFloatingCandidates()
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Mode Management & Panel Expand
    // ---------------------------------------------------------------------------------------------

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
            InputMode.HIRAGANA -> "かなモード"
            InputMode.ALPHANUMERIC -> "英数モード"
            InputMode.NUMERIC -> "数字モード"
        }
        if (::composingTextView.isInitialized) {
            composingTextView.text = modeText
        }
        clearCandidateBar(inputMode != InputMode.HIRAGANA)
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

        if (::candidateFlowLayout.isInitialized) {
            updateCandidateLayoutContainer(candidateFlowLayout, lastHiragana, lastCandidates, lastSelectedIndex)
            if (::expandedScrollView.isInitialized) {
                scrollToSelectedCandidateVertical(expandedScrollView, candidateFlowLayout, lastSelectedIndex)
            }
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

    fun hideEmojiPalette() {
        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized && emojiPaletteController.isVisible) {
            emojiPaletteController.hide(mainKeyboardContainer)
        }
    }

    fun resetKeyboardState() {
        hideEmojiPalette()
        collapseCandidates()
        dismissFloatingCandidates()
    }

    fun toggleEmojiPalette() {
        collapseCandidates()
        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            val wasVisible = emojiPaletteController.isVisible
            val isHardKb = listener?.isHardKeyboardConnected() ?: false
            emojiPaletteController.toggle(
                mainKeyboardContainer,
                isHardKeyboard = isHardKb,
                symbolMode = (inputModeState != InputMode.HIRAGANA)
            )
            if (wasVisible) {
                updateKeyboardLayoutVisibility()
            }
        }
    }

    fun toggleSymbolPalette() {
        collapseCandidates()
        if (::emojiPaletteController.isInitialized && ::mainKeyboardContainer.isInitialized) {
            val wasVisible = emojiPaletteController.isVisible
            val isHardKb = listener?.isHardKeyboardConnected() ?: false
            emojiPaletteController.toggle(
                mainKeyboardContainer,
                isHardKeyboard = isHardKb,
                symbolMode = true
            )
            if (wasVisible) {
                updateKeyboardLayoutVisibility()
            }
        }
    }

    fun updateKeyboardLayoutVisibility() {
        if (!::mainKeyboardContainer.isInitialized) return
        if (isCandidateExpanded) {
            mainKeyboardContainer.visibility = GONE
            return
        }

        val isHardKb = listener?.isHardKeyboardConnected() ?: false
        val isIntegrated = listener?.isIntegratedHardKeyboardConnected() ?: false
        if (isHardKb && !isIntegrated) {
            mainKeyboardContainer.visibility = GONE
        } else {
            mainKeyboardContainer.visibility = VISIBLE
        }

        val isStatusBarEnabled = listener?.getSettings()?.isShowInputStatusBarEnabled ?: true
        val statusVisibility = if (isHardKb || !isStatusBarEnabled) GONE else VISIBLE

        if (::composingTextView.isInitialized) {
            composingTextView.visibility = statusVisibility
        }

        if (isHardKb || lastCandidates.isEmpty()) {
            showToolbar()
        } else {
            showCandidateBar()
        }

        if (!isHardKb) {
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

    fun dismissFloatingCandidates() {
        floatingOverlay.dismiss()
    }
}
