package io.github.kachaya.karukan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 画面キーボードの構築オーケストレーションを行う Builder クラス。
 * キーボードの実体構築は各 [KeyboardLayoutProvider] に委任されます。
 */
class KeyboardLayoutBuilder(
    private val context: Context,
    private val isDarkThemeSupplier: () -> Boolean,
    private val keyButtonHeightSupplier: () -> Int,
    private val keyboardHeightPxSupplier: () -> Int = { keyButtonHeightSupplier() * 5 },
    private val isEnglishModeSupplier: () -> Boolean = { false },
    private val inputModeSupplier: () -> InputMode = { if (isEnglishModeSupplier()) InputMode.ALPHANUMERIC else InputMode.HIRAGANA },
    private val listener: KeyboardEventListener
) {

    // ---------------------------------------------------------------------------------------------
    // Region: Properties & Setup
    // ---------------------------------------------------------------------------------------------

    interface KeyboardEventListener : io.github.kachaya.karukan.KeyboardEventListener

    var onContainerSwapRequested: ((LinearLayout) -> Unit)? = null
    private var currentInputMode: InputMode = inputModeSupplier()
    private var activeProvider: KeyboardLayoutProvider? = null

    private fun createCreateParams(): KeyboardCreateParams {
        return KeyboardCreateParams(
            context = context,
            isDarkSupplier = isDarkThemeSupplier,
            keyButtonHeightSupplier = keyButtonHeightSupplier,
            currentInputModeSupplier = { currentInputMode },
            isEnglishModeSupplier = isEnglishModeSupplier,
            listener = listener,
            setupRepeatableTouchListener = { btn -> setupRepeatableTouchListener(btn) },
            onContainerSwapRequested = onContainerSwapRequested
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Region: Container Creation & Mode Management
    // ---------------------------------------------------------------------------------------------

    fun createContainerForLayout(layoutName: String): LinearLayout {
        val provider = KeyboardLayoutRegistry.getProvider(layoutName)
        activeProvider = provider
        currentInputMode = inputModeSupplier()
        return provider.createContainer(createCreateParams())
    }

    fun updateLanguageMode(isEnglishMode: Boolean) {
        activeProvider?.updateLanguageMode(isEnglishMode)
    }

    fun updateInputMode(inputMode: InputMode) {
        currentInputMode = inputMode
        activeProvider?.updateInputMode(inputMode)
    }

    fun updateNikoInputState(hasInput: Boolean) {
        activeProvider?.updateInputState(hasInput)
    }

    fun createQwertyKeyboardContainer(): LinearLayout = createContainerForLayout("qwerty")
    fun createTabletKeyboardContainer(): LinearLayout = createContainerForLayout("tablet")
    fun createBuiltinKeyboardContainer(): LinearLayout = createContainerForLayout("builtin")
    fun createGraffitiKeyboardContainer(): LinearLayout = createContainerForLayout("graffiti")
    fun createNikoKeyboardContainer(): LinearLayout = createContainerForLayout("niko")

    // ---------------------------------------------------------------------------------------------
    // Region: Repeatable Touch Listener
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatableTouchListener(btn: KeyButton) {
        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null
        var isHeld: Boolean

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    btn.performClick()

                    runnable = object : Runnable {
                        override fun run() {
                            if (isHeld) {
                                btn.performClick()
                                handler.postDelayed(this, 70) // 繰り返し間隔 (70ms)
                            }
                        }
                    }
                    handler.postDelayed(runnable!!, 400) // 初回長押しディレイ (400ms)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHeld = false
                    runnable?.let { handler.removeCallbacks(it) }
                    runnable = null
                    true
                }

                else -> false
            }
        }
    }
}
