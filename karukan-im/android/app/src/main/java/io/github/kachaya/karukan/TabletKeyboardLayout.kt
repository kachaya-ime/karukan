package io.github.kachaya.karukan

object TabletKeyboardLayout {
    fun getLayoutSpec(): List<List<KeySpec>> {
        return listOf(
            // 1段目 (Row 0): Esc, 1~0, -, =, BS
            listOf(
                KeySpec("Esc", normalType = KeyType.ESC, shiftType = KeyType.ESC),
                KeySpec("1", shiftText = "!"),
                KeySpec("2", shiftText = "@"),
                KeySpec("3", shiftText = "#"),
                KeySpec("4", shiftText = "$"),
                KeySpec("5", shiftText = "%"),
                KeySpec("6", shiftText = "^"),
                KeySpec("7", shiftText = "&"),
                KeySpec("8", shiftText = "*"),
                KeySpec("9", shiftText = "("),
                KeySpec("0", shiftText = ")"),
                KeySpec("-", shiftText = "_"),
                KeySpec("=", shiftText = "+"),
                KeySpec("⌫", normalType = KeyType.BACKSPACE, shiftType = KeyType.BACKSPACE, weight = 1.5f, repeatable = true)
            ),
            // 2段目 (Row 1): Tab, q~p, [, ], \
            listOf(
                KeySpec("Tab", normalType = KeyType.TAB, shiftType = KeyType.TAB, weight = 1.5f),
                KeySpec("q", shiftText = "Q"),
                KeySpec("w", shiftText = "W"),
                KeySpec("e", shiftText = "E"),
                KeySpec("r", shiftText = "R"),
                KeySpec("t", shiftText = "T"),
                KeySpec("y", shiftText = "Y"),
                KeySpec("u", shiftText = "U"),
                KeySpec("i", shiftText = "I"),
                KeySpec("o", shiftText = "O"),
                KeySpec("p", shiftText = "P"),
                KeySpec("[", shiftText = "{"),
                KeySpec("]", shiftText = "}"),
                KeySpec("\\", shiftText = "|")
            ),
            // 3段目 (Row 2): Caps, a~l, ;, ', Enter
            listOf(
                KeySpec.gap(0.8f),
                KeySpec("`", shiftText = "~"),
                KeySpec("a", shiftText = "A"),
                KeySpec("s", shiftText = "S"),
                KeySpec("d", shiftText = "D"),
                KeySpec("f", shiftText = "F"),
                KeySpec("g", shiftText = "G"),
                KeySpec("h", shiftText = "H"),
                KeySpec("j", shiftText = "J"),
                KeySpec("k", shiftText = "K"),
                KeySpec("l", shiftText = "L"),
                KeySpec(";", shiftText = ":"),
                KeySpec("'", shiftText = "\""),
                KeySpec("⏎", normalType = KeyType.ENTER, shiftType = KeyType.ENTER, weight = 1.7f)
            ),
            // 4段目 (Row 3): Shift, z~m, ,, ., /, ↑, Shift
            listOf(
                KeySpec("⇧", normalType = KeyType.SHIFT, shiftType = KeyType.SHIFT, weight = 2.3f),
                KeySpec("z", shiftText = "Z"),
                KeySpec("x", shiftText = "X"),
                KeySpec("c", shiftText = "C"),
                KeySpec("v", shiftText = "V"),
                KeySpec("b", shiftText = "B"),
                KeySpec("n", shiftText = "N"),
                KeySpec("m", shiftText = "M"),
                KeySpec(",", shiftText = "<"),
                KeySpec(".", shiftText = ">"),
                KeySpec("/", shiftText = "?"),
                KeySpec("↑", normalType = KeyType.DPAD_UP, shiftType = KeyType.DPAD_UP, repeatable = true, weight = 1.1f),
                KeySpec("⇧", normalType = KeyType.SHIFT, shiftType = KeyType.SHIFT, weight = 1.1f)
            ),
            // 5段目 (Row 4): Ctrl, `, 絵文字, Space, ←, ↓, →
            listOf(
                KeySpec("Ctrl", normalType = KeyType.CTRL, shiftType = KeyType.CTRL, weight = 1.5f),
                KeySpec("あa", normalType = KeyType.LANG, weight = 1.5f),
                KeySpec("😊", normalType = KeyType.EMOJI, shiftType = KeyType.EMOJI, weight = 1.5f),
                KeySpec("⌴", normalType = KeyType.SPACE, shiftType = KeyType.SPACE, weight = 5.2f),
                KeySpec("Ctrl", normalType = KeyType.CTRL, shiftType = KeyType.CTRL, weight = 1.5f),
                KeySpec("←", normalType = KeyType.DPAD_LEFT, shiftType = KeyType.DPAD_LEFT, repeatable = true, weight = 1.1f),
                KeySpec("↓", normalType = KeyType.DPAD_DOWN, shiftType = KeyType.DPAD_DOWN, repeatable = true, weight = 1.1f),
                KeySpec("→", normalType = KeyType.DPAD_RIGHT, shiftType = KeyType.DPAD_RIGHT, repeatable = true, weight = 1.1f)
            )
        )
    }
}