package io.github.kachaya.karukan

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat

/**
 * Karukan IME 全体（入力ビュー、候補バー、キーボード、絵文字パレット、手書き領域、ダイアログ等）で
 * 共通利用するテーマカラー定義
 */
data class ThemeColors(
    val isDark: Boolean,

    // 背景・ベースカラー
    val bg: Int,
    val textColor: Int,
    val statusText: Int,

    // 候補バー・ツールバー・ヘッダー領域
    val candidateBg: Int,
    val candidateText: Int,
    val candidateSelectedBg: Int,
    val candidateSelectedText: Int,
    val toolbarBg: Int,

    // キーボードキー要素
    val keyBg: Int,
    val actionKeyBg: Int,
    val keyPressedBg: Int,
    val actionKeyPressedBg: Int,
    val activeKeyBg: Int,
    val activeKeyPressedBg: Int,
    val keyText: Int,
    val keySubText: Int,
    val activeKeyText: Int,
    val disabledBg: Int,

    // アクティブ選択・ハイライト・アクセント領域
    val activeAccentText: Int,

    // 枠線・区切り線・ボタン構成要素
    val strokeBorderColor: Int,
    val dividerColor: Int,
    val btnCloseBg: Int,
    val btnHelpBg: Int,

    // 手書き (Graffiti) 専用カラー
    val graffitiGuideText: Int,
    val graffitiIndicator: Int,

    // フォーカス枠カラー
    val emojiFocusStroke: Int
) {
    companion object {
        /**
         * isDark の判定結果に応じた Context から ThemeColors を一元取得
         */
        fun from(context: Context, isDark: Boolean): ThemeColors {
            val targetContext = getThemeContext(context, isDark)
            return ThemeColors(
                isDark = isDark,
                bg = ContextCompat.getColor(targetContext, R.color.theme_bg),
                textColor = ContextCompat.getColor(targetContext, R.color.theme_text_color),
                statusText = ContextCompat.getColor(targetContext, R.color.theme_status_text),

                candidateBg = ContextCompat.getColor(targetContext, R.color.theme_candidate_bg),
                candidateText = ContextCompat.getColor(targetContext, R.color.theme_candidate_text),
                candidateSelectedBg = ContextCompat.getColor(targetContext, R.color.theme_candidate_selected_bg),
                candidateSelectedText = ContextCompat.getColor(targetContext, R.color.theme_candidate_selected_text),
                toolbarBg = ContextCompat.getColor(targetContext, R.color.theme_toolbar_bg),

                keyBg = ContextCompat.getColor(targetContext, R.color.theme_key_bg),
                actionKeyBg = ContextCompat.getColor(targetContext, R.color.theme_action_key_bg),
                keyPressedBg = ContextCompat.getColor(targetContext, R.color.theme_key_pressed_bg),
                actionKeyPressedBg = ContextCompat.getColor(targetContext, R.color.theme_action_key_pressed_bg),
                activeKeyBg = ContextCompat.getColor(targetContext, R.color.theme_active_key_bg),
                activeKeyPressedBg = ContextCompat.getColor(targetContext, R.color.theme_active_key_pressed_bg),
                keyText = ContextCompat.getColor(targetContext, R.color.theme_key_text),
                keySubText = ContextCompat.getColor(targetContext, R.color.theme_key_sub_text),
                activeKeyText = ContextCompat.getColor(targetContext, R.color.theme_active_key_text),
                disabledBg = ContextCompat.getColor(targetContext, R.color.theme_disabled_bg),

                activeAccentText = ContextCompat.getColor(targetContext, R.color.theme_active_accent_text),
                strokeBorderColor = ContextCompat.getColor(targetContext, R.color.theme_stroke_border),
                dividerColor = ContextCompat.getColor(targetContext, R.color.theme_divider),
                btnCloseBg = ContextCompat.getColor(targetContext, R.color.theme_btn_close_bg),
                btnHelpBg = ContextCompat.getColor(targetContext, R.color.theme_btn_help_bg),

                graffitiGuideText = ContextCompat.getColor(targetContext, R.color.theme_graffiti_guide_text),
                graffitiIndicator = ContextCompat.getColor(targetContext, R.color.theme_graffiti_indicator),

                emojiFocusStroke = ContextCompat.getColor(targetContext, R.color.theme_emoji_focus_stroke)
            )
        }

        private fun getThemeContext(context: Context, isDark: Boolean): Context {
            val config = Configuration(context.resources.configuration)
            val nightMode = if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            return context.createConfigurationContext(config)
        }
    }
}
