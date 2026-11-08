package io.github.kachaya.karukan

import android.content.Context
import android.widget.LinearLayout

/**
 * 画面キーボード生成用パラメータを保持するデータクラス。
 */

// -------------------------------------------------------------------------------------------------
// Region: KeyboardCreateParams Data Class & Factory
// -------------------------------------------------------------------------------------------------

data class KeyboardCreateParams(
    val context: Context,
    val isDarkSupplier: () -> Boolean,
    val keyButtonHeightSupplier: () -> Int,
    val currentInputModeSupplier: () -> InputMode,
    val isEnglishModeSupplier: () -> Boolean,
    val listener: KeyboardEventListener,
    val setupRepeatableTouchListener: (KeyButton) -> Unit,
    val onContainerSwapRequested: ((LinearLayout) -> Unit)? = null
) {
    /**
     * テーマや入力モードの設定を自動反映した共通 KeyButton を生成します。
     */
    fun createKeyButton(
        spec: KeySpec,
        customHeight: Int = keyButtonHeightSupplier()
    ): KeyButton {
        val isDark = isDarkSupplier()
        val isEnglish = isEnglishModeSupplier()
        val inputMode = currentInputModeSupplier()
        val keyTextColor = KeyboardColors.get(isDark).keyText

        return KeyButton(
            context = context,
            spec = spec,
            keyHeight = customHeight,
            keyTextColor = keyTextColor,
            isDark = isDark,
            onFeedback = { listener.onKeyFeedback() }
        ).apply {
            updateLanguageMode(isEnglish, isDark)
            updateInputMode(inputMode, isDark)
            updateMode(QwertyMode.NORMAL, isDark)
        }.also { btn ->
            if (spec.repeatable) {
                setupRepeatableTouchListener(btn)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Region: KeyboardLayoutProvider Interface
// -------------------------------------------------------------------------------------------------

/**
 * すべての画面キーボードレイアウト（QWERTY, Tablet, Builtin, NikoTouch, Graffiti 等）の抽象化インターフェース。
 */
interface KeyboardLayoutProvider {
    /** レイアウト識別名 ("qwerty", "tablet", "builtin", "niko", "graffiti" 等) */
    val layoutId: String

    /** 高さ計算等に使用するキーボード行数 (例: Standard=5, Graffiti=4) */
    val rowCount: Int
        get() = 5

    /** キーボードコンテナ (LinearLayout) を構築します */
    fun createContainer(params: KeyboardCreateParams): LinearLayout

    /** 入力モード (HIRAGANA, ALPHANUMERIC, NUMERIC) の変更を通知します */
    fun updateInputMode(inputMode: InputMode) {}

    /** 言語モード (日本語/英語) の変更を通知します */
    fun updateLanguageMode(isEnglishMode: Boolean) {}

    /** 未確定入力の有無 state の変更を通知します (ニコタッチ等で使用) */
    fun updateInputState(hasInput: Boolean) {}
}
