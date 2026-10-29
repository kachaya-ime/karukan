package io.github.kachaya.karukan

/**
 * 画面キーボードおよび物理キーボードからの操作を表す抽象キーアクション。
 */
sealed interface KeyAction {
    data class InputChar(val text: String, val isShift: Boolean = false) : KeyAction
    data class NikoTouchInput(val char: String) : KeyAction
    data object NikoTouchToggle : KeyAction

    data object Backspace : KeyAction
    data object DeleteForward : KeyAction
    data object DeleteSelectedCandidate : KeyAction

    data object SpaceOrConvert : KeyAction
    data object FullWidthSpace : KeyAction
    data class Commit(val isShift: Boolean = false) : KeyAction
    data object Escape : KeyAction
    data class Tab(val isShift: Boolean = false) : KeyAction

    data object CommitHiragana : KeyAction
    data object CommitKatakana : KeyAction
    data object CommitHankakuKatakana : KeyAction
    data object CommitZenkakuAlphanumeric : KeyAction
    data object CommitHankakuAlphanumeric : KeyAction

    data object ToggleLanguageMode : KeyAction
    data object FixChunk : KeyAction
    data object KatakanaHiraganaToggle : KeyAction
    data object ToggleEmojiPalette : KeyAction
    data object ToggleSymbolPalette : KeyAction
    data object ToggleDetailDisplay : KeyAction
    data object ToggleLiveConversion : KeyAction
    data object ToggleCandidateExpand : KeyAction
    data object Reconvert : KeyAction
    data object SelectAll : KeyAction

    data class SelectCandidateNumber(val index: Int) : KeyAction

    data class MoveLeft(val isShift: Boolean = false) : KeyAction
    data class MoveRight(val isShift: Boolean = false) : KeyAction
    data class MoveUp(val isShift: Boolean = false) : KeyAction
    data class MoveDown(val isShift: Boolean = false) : KeyAction
    data object MoveHome : KeyAction
    data object MoveEnd : KeyAction
    data object PageUp : KeyAction
    data object PageDown : KeyAction

    data class CtrlShortcut(val key: String) : KeyAction
}
