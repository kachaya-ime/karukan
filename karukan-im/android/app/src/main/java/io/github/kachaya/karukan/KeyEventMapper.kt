package io.github.kachaya.karukan

import android.view.KeyEvent

/**
 * Android の KeyEvent を KeyAction に変換するマッパー。
 */
object KeyEventMapper {

    /**
     * 与えられた [keyCode] および [event] から対応する [KeyAction] を返します。
     * マッピングできるアクションがない場合は null を返します。
     */
    fun fromKeyEvent(keyCode: Int, event: KeyEvent?): KeyAction? {
        if (event == null) return null

        // 1. Ctrl ショートカットの優先判定
        if (event.isCtrlPressed) {
            if (event.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) {
                return KeyAction.ToggleDetailDisplay
            }
            if (event.isShiftPressed && keyCode == KeyEvent.KEYCODE_L) {
                return KeyAction.CtrlShortcut("l")
            }
            if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                return KeyAction.DeleteSelectedCandidate
            }
            if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                return KeyAction.SelectCandidateNumber(keyCode - KeyEvent.KEYCODE_1)
            }
            if (keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9) {
                return KeyAction.SelectCandidateNumber(keyCode - KeyEvent.KEYCODE_NUMPAD_1)
            }

            return getCtrlShortcutChar(event)?.let { KeyAction.CtrlShortcut(it) }
        }

        // 2. 特殊操作キーの判定
        when (keyCode) {
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
            KeyEvent.KEYCODE_HENKAN -> return KeyAction.SpaceOrConvert
            KeyEvent.KEYCODE_MUHENKAN -> return KeyAction.CommitKatakana
            KeyEvent.KEYCODE_KATAKANA_HIRAGANA, KeyEvent.KEYCODE_KANA -> return KeyAction.KatakanaHiraganaToggle
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU, KeyEvent.KEYCODE_EISU -> return KeyAction.ToggleLanguageMode
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

        // 3. 印刷可能文字（通常キータイピング）
        // Android OS の Physical Keyboard Layout 判定結果 (event.unicodeChar) を最優先にする。
        // これにより、JIS（日本語）配列・US（英語）配列のいずれの物理キーボードでも正確な文字・記号が得られる。
        val unicode = event.unicodeChar
        if (unicode in 32..126) {
            val charStr = unicode.toChar().toString()
            return KeyAction.InputChar(charStr, event.isShiftPressed)
        }

        return null
    }

    /**
     * Ctrl ショートカット用の文字を取得します。
     */
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
