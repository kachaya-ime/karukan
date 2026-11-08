package io.github.kachaya.karukan

import android.view.KeyEvent

/**
 * Android の KeyEvent を KeyAction に変換するマッパー。
 */
object KeyEventMapper {

    // ---------------------------------------------------------------------------------------------
    // Region: Scancode & JIS Character Maps
    // ---------------------------------------------------------------------------------------------

    private val scancodeToKeyCodeMap = mapOf(
        148 to KeyEvent.KEYCODE_ZENKAKU_HANKAKU, // 半角/全角
        41 to KeyEvent.KEYCODE_ZENKAKU_HANKAKU,  // 半角/全角 (109 keyboard top-left key)
        92 to KeyEvent.KEYCODE_HENKAN,           // 変換
        94 to KeyEvent.KEYCODE_MUHENKAN,         // 無変換
        208 to KeyEvent.KEYCODE_KATAKANA_HIRAGANA, // カタカナ/ひらがな
        124 to KeyEvent.KEYCODE_YEN,             // ￥
        89 to KeyEvent.KEYCODE_RO                // ろ / バックスラッシュキー
    )

    private val jisCharacterMap = mapOf(
        KeyEvent.KEYCODE_1 to Pair('1', '!'),
        KeyEvent.KEYCODE_2 to Pair('2', '"'),
        KeyEvent.KEYCODE_3 to Pair('3', '#'),
        KeyEvent.KEYCODE_4 to Pair('4', '$'),
        KeyEvent.KEYCODE_5 to Pair('5', '%'),
        KeyEvent.KEYCODE_6 to Pair('6', '&'),
        KeyEvent.KEYCODE_7 to Pair('7', '\''),
        KeyEvent.KEYCODE_8 to Pair('8', '('),
        KeyEvent.KEYCODE_9 to Pair('9', ')'),
        KeyEvent.KEYCODE_0 to Pair('0', '0'),
        KeyEvent.KEYCODE_MINUS to Pair('-', '='),
        KeyEvent.KEYCODE_EQUALS to Pair('^', '~'),
        KeyEvent.KEYCODE_YEN to Pair('\u00A5', '|'),
        KeyEvent.KEYCODE_LEFT_BRACKET to Pair('@', '`'),
        KeyEvent.KEYCODE_RIGHT_BRACKET to Pair('[', '{'),
        KeyEvent.KEYCODE_SEMICOLON to Pair(';', '+'),
        KeyEvent.KEYCODE_APOSTROPHE to Pair(':', '*'),
        KeyEvent.KEYCODE_BACKSLASH to Pair(']', '}'),
        KeyEvent.KEYCODE_COMMA to Pair(',', '<'),
        KeyEvent.KEYCODE_PERIOD to Pair('.', '>'),
        KeyEvent.KEYCODE_SLASH to Pair('/', '?'),
        KeyEvent.KEYCODE_RO to Pair('\\', '_')
    )

    // ---------------------------------------------------------------------------------------------
    // Region: KeyEvent to KeyAction Mapper
    // ---------------------------------------------------------------------------------------------

    fun fromKeyEvent(keyCode: Int, event: KeyEvent?, physicalKeyboardLayout: String = "jis109"): KeyAction? {
        if (event == null) return null

        val isJis109 = physicalKeyboardLayout == "jis109"
        val scanCode = event.scanCode
        val mappedKeyCode = if (isJis109 && scanCode != 0) {
            scancodeToKeyCodeMap[scanCode] ?: keyCode
        } else {
            keyCode
        }

        if (event.isCtrlPressed) {
            if (mappedKeyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                return KeyAction.DeleteSelectedCandidate
            }
            if (mappedKeyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                return KeyAction.SelectCandidateNumber(mappedKeyCode - KeyEvent.KEYCODE_1)
            }
            if (mappedKeyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9) {
                return KeyAction.SelectCandidateNumber(mappedKeyCode - KeyEvent.KEYCODE_NUMPAD_1)
            }
            if (mappedKeyCode == KeyEvent.KEYCODE_SPACE) {
                return KeyAction.ToggleLanguageMode
            }

            return getCtrlShortcutChar(event)?.let { KeyAction.CtrlShortcut(it) }
        }

        when (mappedKeyCode) {
            KeyEvent.KEYCODE_DEL -> return KeyAction.Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> return KeyAction.DeleteForward
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return KeyAction.Commit(event.isShiftPressed)
            KeyEvent.KEYCODE_SPACE -> return if (event.isShiftPressed) KeyAction.FullWidthSpace else KeyAction.SpaceOrConvert
            KeyEvent.KEYCODE_F3 -> return KeyAction.ToggleLanguageMode
            KeyEvent.KEYCODE_F4 -> return KeyAction.ToggleEmojiPalette
            KeyEvent.KEYCODE_F6 -> return KeyAction.CommitHiragana
            KeyEvent.KEYCODE_F7 -> return KeyAction.CommitKatakana
            KeyEvent.KEYCODE_F8 -> return KeyAction.CommitHankakuKatakana
            KeyEvent.KEYCODE_F9 -> return KeyAction.CommitZenkakuAlphanumeric
            KeyEvent.KEYCODE_F10 -> return KeyAction.CommitHankakuAlphanumeric
            KeyEvent.KEYCODE_ESCAPE -> return KeyAction.Escape
            KeyEvent.KEYCODE_TAB -> return KeyAction.Tab(event.isShiftPressed)
            KeyEvent.KEYCODE_HENKAN -> return KeyAction.Henkan
            KeyEvent.KEYCODE_MUHENKAN -> return KeyAction.CommitKatakana
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA, KeyEvent.KEYCODE_KANA -> return KeyAction.KanaMode
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> return KeyAction.ToggleLanguageMode
            KeyEvent.KEYCODE_EISU -> return KeyAction.EisuMode
            KeyEvent.KEYCODE_STAR -> return KeyAction.NikoTouchToggle
            KeyEvent.KEYCODE_POUND -> return KeyAction.SpaceOrConvert
            KeyEvent.KEYCODE_DPAD_LEFT -> return KeyAction.MoveLeft(event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return KeyAction.MoveRight(event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_UP -> return KeyAction.MoveUp(event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> return KeyAction.MoveDown(event.isShiftPressed)
            KeyEvent.KEYCODE_MOVE_HOME -> return KeyAction.MoveHome
            KeyEvent.KEYCODE_MOVE_END -> return KeyAction.MoveEnd
            KeyEvent.KEYCODE_PAGE_UP -> return KeyAction.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> return KeyAction.PageDown
            KeyEvent.KEYCODE_SYM -> return KeyAction.ToggleEmojiPalette
        }

        if (isJis109) {
            val jisPair = jisCharacterMap[mappedKeyCode]
            if (jisPair != null) {
                val charToInput = if (event.isShiftPressed) jisPair.second else jisPair.first
                return KeyAction.InputChar(charToInput.toString(), false)
            }
        }

        val unicode = event.unicodeChar
        if (unicode in 32..126) {
            val charStr = unicode.toChar().toString()
            return KeyAction.InputChar(charStr, event.isShiftPressed)
        }

        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Region: KeyEvent to Native Keysym/Modifiers
    // ---------------------------------------------------------------------------------------------

    fun toNativeKeysymAndModifiers(keyCode: Int, event: KeyEvent?, physicalKeyboardLayout: String = "jis109"): Pair<Int, Int>? {
        if (event == null) return null

        val isJis109 = physicalKeyboardLayout == "jis109"
        val scanCode = event.scanCode
        val mappedKeyCode = if (isJis109 && scanCode != 0) {
            scancodeToKeyCodeMap[scanCode] ?: keyCode
        } else {
            keyCode
        }

        var modifiers = 0
        if (event.isShiftPressed) modifiers = modifiers or 1
        if (event.isCtrlPressed) modifiers = modifiers or 4
        if (event.isAltPressed) modifiers = modifiers or 8

        val keysym = when (mappedKeyCode) {
            KeyEvent.KEYCODE_DEL -> 0xff08
            KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
            KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) 0xfe20 else 0xff09
            KeyEvent.KEYCODE_SPACE -> 0x0020
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
            KeyEvent.KEYCODE_MOVE_END -> 0xff57
            KeyEvent.KEYCODE_PAGE_UP -> 0xff55
            KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            KeyEvent.KEYCODE_HENKAN -> 0xff23
            KeyEvent.KEYCODE_MUHENKAN -> 0xff22
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA, KeyEvent.KEYCODE_KANA -> 0xff27
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> 0xff29
            else -> {
                if (isJis109) {
                    val jisPair = jisCharacterMap[mappedKeyCode]
                    if (jisPair != null) {
                        val charToInput = if (event.isShiftPressed) jisPair.second else jisPair.first
                        charToInput.code
                    } else null
                } else null
            }
        }

        if (keysym != null) {
            return Pair(keysym, modifiers)
        }

        val unicode = event.unicodeChar
        if (unicode in 32..126) {
            return Pair(unicode, modifiers)
        }

        return null
    }

    fun charToNativeKeysym(char: Char): Int {
        return char.code
    }

    private fun getCtrlShortcutChar(event: KeyEvent): String? {
        val unmod = event.getUnicodeChar(0)
        if (unmod in 32..126) {
            return unmod.toChar().lowercaseChar().toString()
        }
        val keyCode = event.keyCode
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        return null
    }
}
