package io.github.kachaya.karukan

object QwertyKeyboardLayout {
    fun getLayoutSpec(): List<List<KeySpec>> {
        return listOf(
            // 1段目 (Row 0): 数字行 (10キー)
            listOf(
                KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
                KeySpec("6"), KeySpec("7"), KeySpec("8"), KeySpec("9"), KeySpec("0")
            ),
            // 2段目 (Row 1): QWERTY上段キー列 (q .. p, 10キー)
            listOf(
                KeySpec("q", shiftText = "Q", symbolText = "~"),
                KeySpec("w", shiftText = "W", symbolText = "@"),
                KeySpec("e", shiftText = "E", symbolText = "#"),
                KeySpec("r", shiftText = "R", symbolText = "$"),
                KeySpec("t", shiftText = "T", symbolText = "%"),
                KeySpec("y", shiftText = "Y", symbolText = "^"),
                KeySpec("u", shiftText = "U", symbolText = "&"),
                KeySpec("i", shiftText = "I", symbolText = "*"),
                KeySpec("o", shiftText = "O", symbolText = "("),
                KeySpec("p", shiftText = "P", symbolText = ")")
            ),
            // 3段目 (Row 2): QWERTY中段キー列 (a .. -, 10キー)
            listOf(
                KeySpec("a", shiftText = "A", symbolText = "+"),
                KeySpec("s", shiftText = "S", symbolText = "="),
                KeySpec("d", shiftText = "D", symbolText = "/"),
                KeySpec("f", shiftText = "F", symbolText = "\\"),
                KeySpec("g", shiftText = "G", symbolText = "<"),
                KeySpec("h", shiftText = "H", symbolText = ">"),
                KeySpec("j", shiftText = "J", symbolText = "{"),
                KeySpec("k", shiftText = "K", symbolText = "}"),
                KeySpec("l", shiftText = "L", symbolText = "["),
                KeySpec("-", shiftText = "_", symbolText = "]")
            ),
            // 4段目 (Row 3): QWERTY下段キー列 (Shift/Esc, z .. m, ., BS, 10キー)
            listOf(
                KeySpec(
                    normalText = "⇧",
                    shiftText = "⇧",
                    symbolText = "esc",
                    normalType = KeyType.SHIFT,
                    shiftType = KeyType.SHIFT,
                    symbolType = KeyType.ESC
                ),
                KeySpec("z", shiftText = "Z", symbolText = "|"),
                KeySpec("x", shiftText = "X", symbolText = "`"),
                KeySpec("c", shiftText = "C", symbolText = "'"),
                KeySpec("v", shiftText = "V", symbolText = "\""),
                KeySpec("b", shiftText = "B", symbolText = ";"),
                KeySpec("n", shiftText = "N", symbolText = ":"),
                KeySpec("m", shiftText = "M", symbolText = "!"),
                KeySpec(".", shiftText = ",", symbolText = "?"),
                KeySpec("⌫", normalType = KeyType.BACKSPACE, repeatable = true)
            ),
            // 5段目 (Row 4): アクション行 (Ctrl, @!?/ABCトグル, あa/Tab, 幅広Space, 絵文字, 左右矢印, Enter / 文字キーなし・10スロット構成)
            listOf(
                KeySpec("ctrl", KeyType.CTRL),
                KeySpec(
                    normalText = "@!?",
                    shiftText = "@!?",
                    symbolText = "abc",
                    normalType = KeyType.SYMBOL,
                    shiftType = KeyType.SYMBOL,
                    symbolType = KeyType.NORMAL
                ),
                KeySpec(
                    normalText = "あa",
                    shiftText = "あa",
                    symbolText = "tab",
                    normalType = KeyType.LANG,
                    shiftType = KeyType.LANG,
                    symbolType = KeyType.TAB
                ),
                KeySpec(
                    normalText = "⌴",
                    shiftText = "⌴",
                    symbolText = "⌴",
                    normalType = KeyType.SPACE,
                    shiftType = KeyType.SPACE,
                    symbolType = KeyType.SPACE,
                    weight = 3.0f
                ),
                KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24),
                KeySpec("←", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("→", KeyType.DPAD_RIGHT, repeatable = true),
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }
}
