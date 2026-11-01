package io.github.kachaya.karukan

/**
 * Unihertz Titan や Clicks などの内蔵物理キーボード端末向け専用画面キーボードレイアウト。
 * 物理キーボードから直接入力が困難な記号 (<, >, [, ], {, }, ;, = など) を
 * 画面上で素早く入力できるように配置しています。
 */
object BuiltinKeyboardLayout {
    fun getLayoutSpec(): List<List<KeySpec>> {
        return listOf(
            // 1段目 (Row 0): 内蔵物理キーボードで入力が困難な記号
            listOf(
                KeySpec("esc", KeyType.ESC),
                KeySpec("~"),
                KeySpec("$"),
                KeySpec("%"),
                KeySpec("&"),
                KeySpec("^"),
                KeySpec("="),
                KeySpec("|"),
                KeySpec("\\"),
                KeySpec("."),
            ),
            // 2段目 (Row 1): 内蔵物理キーボードで入力が困難な記号
            listOf(
                KeySpec("tab", KeyType.TAB),
                KeySpec("`"),
                KeySpec(";"),
                KeySpec("<"),
                KeySpec(">"),
                KeySpec("["),
                KeySpec("]"),
                KeySpec("{"),
                KeySpec("}"),
                KeySpec("-"),
            ),
        )
    }
}
