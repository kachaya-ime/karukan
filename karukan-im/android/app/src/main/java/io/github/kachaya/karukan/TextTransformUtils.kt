package io.github.kachaya.karukan

/**
 * 文字列の全角/半角変換、アノテーション除去、カッコペア判定を行うユーティリティ関数群です。
 */
object TextTransformUtils {

    /**
     * アノテーションバッジ (" (📝)" や " (🤖 推論)") を除去して純粋な変換候補テキストを取得します。
     */
    fun extractPureCandidate(candidateStr: String): String {
        val lastOpen = candidateStr.lastIndexOf(" (")
        return if (lastOpen > 0 && candidateStr.endsWith(")")) candidateStr.substring(0, lastOpen) else candidateStr
    }

    /**
     * 全角カタカナを半角カタカナに変換します。
     */
    fun toHankakuKatakana(str: String): String {
        val zenkaku = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンァィゥェォッャュョーガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
        val hankaku = arrayOf(
            "ｱ", "ｲ", "ｳ", "ｴ", "ｵ", "ｶ", "ｷ", "ｸ", "ｹ", "ｺ", "ｻ", "ｼ", "ｽ", "ｾ", "ｿ", "ﾀ", "ﾁ", "ﾂ", "ﾃ", "ﾄ", "ﾅ", "ﾆ", "ﾇ", "ﾈ", "ﾉ", "ﾊ", "ﾋ", "ﾌ", "ﾍ", "ﾎ", "ﾏ", "ﾐ", "ﾑ", "ﾒ", "ﾓ", "ﾔ", "ﾕ", "ﾖ", "ﾗ", "ﾘ", "ﾙ", "ﾚ", "ﾛ", "ﾜ", "ｦ", "ﾝ", "ｧ", "ｨ", "ｩ", "ｪ", "ｫ", "ｯ", "ｬ", "ｭ", "ｮ", "ｰ",
            "ｶﾞ", "ｷﾞ", "ｸﾞ", "ｹﾞ", "ｺﾞ", "ｻﾞ", "ｼﾞ", "ｽﾞ", "ｾﾞ", "ｿﾞ", "ﾀﾞ", "ﾁﾞ", "ﾂﾞ", "ﾃﾞ", "ﾄﾞ", "ﾊﾞ", "ﾋﾞ", "ﾌﾞ", "ﾍﾞ", "ﾎﾞ", "ﾊﾟ", "ﾋﾟ", "ﾌﾟ", "ﾍﾟ", "ﾎﾟ"
        )
        val sb = StringBuilder()
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
     * 半角英数記号を全角英数記号に変換します。
     */
    fun toZenkakuAlphanumeric(str: String): String {
        val sb = StringBuilder()
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
        val pairedBrackets = setOf(
            "「」", "『』", "（）", "()", "［］", "[]",
            "｛｝", "{}", "【】", "〈〉", "《》", "“”", "‘’", "””"
        )
        return text in pairedBrackets
    }
}

/**
 * [TextTransformUtils.extractPureCandidate] への便利拡張関数
 */
fun String.extractPureCandidate(): String = TextTransformUtils.extractPureCandidate(this)
