package io.github.kachaya.karukan

/**
 * 文字列の全角/半角変換、アノテーション除去、カッコペア判定を行うユーティリティ関数群です。
 */
object TextTransformUtils {

    /**
     * アノテーションバッジ (" (📝)" や Unit Separator 区切り) を除去して純粋な変換候補テキストを取得します。
     */
    fun extractPureCandidate(candidateStr: String): String {
        if (candidateStr.isEmpty()) return ""
        val usIdx = candidateStr.indexOf('\u001f')
        if (usIdx >= 0) {
            return candidateStr.substring(0, usIdx)
        }
        val lastOpen = candidateStr.lastIndexOf(" (")
        if (lastOpen > 0 && candidateStr.endsWith(")")) {
            return candidateStr.substring(0, lastOpen)
        }
        return candidateStr
    }

    /**
     * 候補文字列内の（）で囲まれた説明部分（アノテーション）に 0.75倍の相対フォントサイズを適用した SpannableString を返します。
     */
    fun formatCandidateText(candidateStr: String): CharSequence {
        val cleanText = candidateStr.replace("\u001f", "")
        val openIdx = cleanText.lastIndexOf('(')
        if (openIdx >= 0 && cleanText.endsWith(')')) {
            val spannable = android.text.SpannableString(cleanText)
            spannable.setSpan(
                android.text.style.RelativeSizeSpan(0.75f),
                openIdx,
                cleanText.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return spannable
        }
        return cleanText
    }

    /**
     * 全角カタカナを半角カタカナに変換します（フォールバック用）。
     */
    fun toHankakuKatakanaFallback(str: String): String {
        val zenkaku = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンァィゥェォッャュョーガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
        val hankaku = arrayOf(
            "ｱ", "ｲ", "ｳ", "ｴ", "ｵ", "ｶ", "ｷ", "ｸ", "ｹ", "ｺ", "ｻ", "ｼ", "ｽ", "ｾ", "ｿ", "ﾀ", "ﾁ", "ﾂ", "ﾃ", "ﾄ", "ﾅ", "ﾆ", "ﾇ", "ﾈ", "ﾉ", "ﾊ", "ﾋ", "ﾌ", "ﾍ", "ﾎ", "ﾏ", "ﾐ", "ﾑ", "ﾒ", "ﾓ", "ﾔ", "ﾕ", "ﾖ", "ﾗ", "ﾘ", "ﾙ", "ﾚ", "ﾛ", "ﾜ", "ｦ", "ﾝ", "ｧ", "ｨ", "ｩ", "ｪ", "ｫ", "ｯ", "ｬ", "ｭ", "ｮ", "ｰ",
            "ｶﾞ", "ｷﾞ", "ｸﾞ", "ｹﾞ", "ｺﾞ", "ｻﾞ", "ｼﾞ", "ｽﾞ", "ｾﾞ", "ｿﾞ", "ﾀﾞ", "ﾁﾞ", "ﾂﾞ", "ﾃﾞ", "ﾄﾞ", "ﾊﾞ", "ﾋﾞ", "ﾌﾞ", "ﾍﾞ", "ﾎﾞ", "ﾊﾟ", "ﾋﾟ", "ﾌﾟ", "ﾍﾟ", "ﾎﾟ"
        )
        val sb = StringBuilder(str.length)
        for (c in str) {
            val idx = zenkaku.indexOf(c)
            if (idx != -1) {
                sb.append(hankaku[idx])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 半角英数記号を全角英数記号に変換します（フォールバック用）。
     */
    fun toZenkakuAlphanumericFallback(str: String): String {
        val sb = StringBuilder(str.length)
        for (c in str) {
            val code = c.code
            if (code in 0x0021..0x007E) {
                sb.append((code + 0xFEE0).toChar())
            } else if (code == 0x0020) {
                sb.append('　')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 2文字のペアカッコ (例: 「」, （）, 【】 等) であるかを判定します。
     */
    fun isPairedBracket(text: String): Boolean {
        if (text.length != 2) return false
        return text in pairedBrackets
    }

    private val pairedBrackets = setOf(
        "「」", "『』", "（）", "()", "［］", "[]",
        "｛｝", "{}", "【】", "〈〉", "《》", "“”", "‘’", "””"
    )
}

/**
 * [TextTransformUtils.extractPureCandidate] への便利拡張関数
 */
fun String.extractPureCandidate(): String = TextTransformUtils.extractPureCandidate(this)
