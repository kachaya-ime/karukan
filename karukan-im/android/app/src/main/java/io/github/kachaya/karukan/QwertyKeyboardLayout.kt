package io.github.kachaya.karukan

object QwertyKeyboardLayout {
    fun getLayoutSpec(): List<List<KeySpec>> {
        return listOf(
            // 1段目 (Row 0): 数字行
            listOf(
                KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
                KeySpec("6"), KeySpec("7"), KeySpec("8"), KeySpec("9"), KeySpec("0")
            ),
            // 2段目 (Row 1): q .. p
            listOf(
                KeySpec("q", shiftText = "Q", symbolText = "/"),
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
            // 3段目 (Row 2): a .. -
            listOf(
                KeySpec("a", shiftText = "A", symbolText = "+"),
                KeySpec("s", shiftText = "S", symbolText = "="),
                KeySpec("d", shiftText = "D", symbolText = "-"),
                KeySpec("f", shiftText = "F", symbolText = "|"),
                KeySpec("g", shiftText = "G", symbolText = "\\"),
                KeySpec("h", shiftText = "H", symbolText = ":"),
                KeySpec("j", shiftText = "J", symbolText = ";"),
                KeySpec("k", shiftText = "K", symbolText = "\""),
                KeySpec("l", shiftText = "L", symbolText = "'"),
                KeySpec("-", shiftText = "_", symbolText = "`")
            ),
            // 4段目 (Row 3): Shift, z..m, , , BS
            listOf(
                KeySpec(
                    normalText = "⇧",
                    shiftText = "⇧",
                    symbolText = "Esc",
                    normalType = KeyType.SHIFT,
                    shiftType = KeyType.SHIFT,
                    symbolType = KeyType.ESC,
                    weight = 1.5f
                ),
                KeySpec("z", shiftText = "Z", symbolText = "~"),
                KeySpec("x", shiftText = "X", symbolText = "<"),
                KeySpec("c", shiftText = "C", symbolText = ">"),
                KeySpec("v", shiftText = "V", symbolText = "{"),
                KeySpec("b", shiftText = "B", symbolText = "}"),
                KeySpec("n", shiftText = "N", symbolText = "["),
                KeySpec("m", shiftText = "M", symbolText = "]"),
                KeySpec("⌫", normalType = KeyType.BACKSPACE, weight = 1.5f, repeatable = true)
            ),
            // 5段目 (Row 4): アクション行
            listOf(
                KeySpec(
                    normalText = "@!?",
                    shiftText = "@!?",
                    symbolText = "ABC",
                    normalType = KeyType.SYMBOL,
                    shiftType = KeyType.SYMBOL,
                    symbolType = KeyType.NORMAL,
                    weight = 1.2f
                ),
                KeySpec(
                    normalText = "Ctrl",
                    shiftText = "Ctrl",
                    symbolText = "Ctrl",
                    normalType = KeyType.CTRL,
                    shiftType = KeyType.CTRL,
                    symbolType = KeyType.CTRL,
                    weight = 1.0f
                ),
                KeySpec(
                    normalText = "あa",
                    symbolText = "Tab",
                    normalType = KeyType.LANG,
                    symbolType = KeyType.TAB,
                    weight = 0.9f
                ),
                KeySpec(",", shiftText = "[", symbolText = "カナ", symbolType = KeyType.KATAKANA, weight = 0.8f),
                KeySpec("⌴", normalType = KeyType.SPACE, weight = 1.8f),
                KeySpec(".", shiftText = "]", symbolText = "?", weight = 0.8f),
                KeySpec("←", normalType = KeyType.DPAD_LEFT, repeatable = true, weight = 0.75f),
                KeySpec("→", normalType = KeyType.DPAD_RIGHT, repeatable = true, weight = 0.75f),
                KeySpec("⏎", normalType = KeyType.ENTER, weight = 1.3f)
            )
        )
    }
}