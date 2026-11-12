package io.github.kachaya.karukan

import android.graphics.Color
import androidx.core.graphics.ColorUtils

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
    val graffitiBg: Int,
    val graffitiGuideText: Int,
    val graffitiStroke: Int,
    val graffitiIndicator: Int
) {
    companion object {
        private val baseAccentGreen = Color.parseColor("#00897B")
        private val baseDarkBg = Color.parseColor("#202124")
        private val baseLightBg = Color.parseColor("#F8F9FA")
        private val baseAccentBlue = Color.parseColor("#1A73E8")
        private val baseTextLight = Color.parseColor("#E8EAED")
        private val baseTextDark = Color.parseColor("#202124")

        // 事前生成済みテーマカラーキャッシュ
        private var cachedDarkColors: ThemeColors? = null
        private var cachedLightColors: ThemeColors? = null

        /**
         * isDark の判定結果に応じた ThemeColors を取得（事前計算キャッシュを返却）
         */
        fun from(isDark: Boolean): ThemeColors {
            return if (isDark) {
                cachedDarkColors ?: createDarkTheme().also { cachedDarkColors = it }
            } else {
                cachedLightColors ?: createLightTheme().also { cachedLightColors = it }
            }
        }

        private fun createDarkTheme(): ThemeColors {
            val bg = baseDarkBg
            val candidateBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.04f)
            val candidateSelectedBg = ColorUtils.blendARGB(bg, baseAccentGreen, 0.30f)

            return ThemeColors(
                isDark = true,
                bg = bg,
                textColor = Color.WHITE,
                statusText = baseTextLight,
                candidateBg = candidateBg,
                candidateText = baseTextLight,
                candidateSelectedBg = candidateSelectedBg,
                candidateSelectedText = Color.WHITE,

                keyBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.11f),
                actionKeyBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.25f),
                keyPressedBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.25f),
                actionKeyPressedBg = ColorUtils.blendARGB(baseDarkBg, Color.WHITE, 0.32f),
                activeKeyBg = ColorUtils.blendARGB(baseDarkBg, baseAccentBlue, 0.70f),
                activeKeyPressedBg = ColorUtils.blendARGB(baseDarkBg, baseAccentBlue, 0.85f),
                keyText = Color.WHITE,
                keySubText = Color.parseColor("#9AA0A6"),
                activeKeyText = baseTextDark,
                disabledBg = baseDarkBg,

                activeAccentText = Color.parseColor("#8AB4F8"),
                strokeBorderColor = Color.parseColor("#5F6368"),
                dividerColor = Color.parseColor("#4A4D51"),
                btnCloseBg = Color.parseColor("#5F6368"),
                btnHelpBg = Color.parseColor("#3C4043"),

                graffitiBg = Color.parseColor("#1F2023"),
                graffitiGuideText = Color.parseColor("#444950"),
                graffitiStroke = Color.parseColor("#8AB4F8"),
                graffitiIndicator = Color.parseColor("#F28B82")
            )
        }

        private fun createLightTheme(): ThemeColors {
            val bg = Color.parseColor("#ECEFF1")
            val candidateBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.04f)
            val candidateSelectedBg = ColorUtils.blendARGB(bg, baseAccentGreen, 0.15f)
            val candidateSelectedText = ColorUtils.blendARGB(baseAccentGreen, Color.BLACK, 0.50f)

            return ThemeColors(
                isDark = false,
                bg = bg,
                textColor = baseTextDark,
                statusText = baseTextDark,
                candidateBg = candidateBg,
                candidateText = baseTextDark,
                candidateSelectedBg = candidateSelectedBg,
                candidateSelectedText = candidateSelectedText,

                keyBg = Color.WHITE,
                actionKeyBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.12f),
                keyPressedBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.08f),
                actionKeyPressedBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.22f),
                activeKeyBg = baseAccentBlue,
                activeKeyPressedBg = ColorUtils.blendARGB(baseAccentBlue, Color.BLACK, 0.20f),
                keyText = baseTextDark,
                keySubText = Color.parseColor("#70757A"),
                activeKeyText = Color.WHITE,
                disabledBg = ColorUtils.blendARGB(baseLightBg, Color.BLACK, 0.04f),

                activeAccentText = baseAccentBlue,
                strokeBorderColor = Color.parseColor("#DADCE0"),
                dividerColor = Color.parseColor("#E0E0E0"),
                btnCloseBg = Color.parseColor("#DADCE0"),
                btnHelpBg = Color.parseColor("#DADCE0"),

                graffitiBg = Color.parseColor("#F1F3F4"),
                graffitiGuideText = Color.parseColor("#D0D4D9"),
                graffitiStroke = baseAccentBlue,
                graffitiIndicator = Color.parseColor("#D93025")
            )
        }
    }
}
