package io.github.kachaya.karukan

/**
 * 画面キーボードおよび物理キーボードからの操作を表す抽象キーアクション。
 */
sealed interface KeyAction {

    // 文字入力アクション
    data class InputChar(val text: String, val isShift: Boolean = false) : KeyAction
    data class KanaInput(val char: String) : KeyAction
    data class NikoTouchInput(val char: String) : KeyAction
    data object NikoTouchToggle : KeyAction

    // 削除・クリアアクション
    data object Backspace : KeyAction
    data object DeleteForward : KeyAction
    data object DeleteSelectedCandidate : KeyAction

    // 移動・入力補助アクション
    data object SpaceOrConvert : KeyAction
    data object FullWidthSpace : KeyAction
    data class Commit(val isShift: Boolean = false) : KeyAction
    data object Escape : KeyAction
    data class Tab(val isShift: Boolean = false) : KeyAction

    // かな・カナ・英数変換決定アクション
    data object CommitHiragana : KeyAction
    data object CommitKatakana : KeyAction
    data object CommitHankakuKatakana : KeyAction
    data object CommitZenkakuAlphanumeric : KeyAction
    data object CommitHankakuAlphanumeric : KeyAction

    // モード切替・トグルアクション
    data object ToggleLanguageMode : KeyAction
    data object KanaMode : KeyAction
    data object EisuMode : KeyAction
    data object Henkan : KeyAction
    data object FixChunk : KeyAction
    data object KatakanaHiraganaToggle : KeyAction
    data object ToggleEmojiPalette : KeyAction
    data object ToggleSymbolPalette : KeyAction
    data object ToggleDetailDisplay : KeyAction
    data object ToggleLiveConversion : KeyAction
    data object ToggleCandidateExpand : KeyAction
    data object Reconvert : KeyAction

    // クリップボード・テキスト選択アクション
    data object SelectAll : KeyAction
    data object Undo : KeyAction
    data object Cut : KeyAction
    data object Copy : KeyAction
    data object Paste : KeyAction

    // 候補選択・カーソル移動アクション
    data class SelectCandidateNumber(val index: Int) : KeyAction
    data class MoveLeft(val isShift: Boolean = false) : KeyAction
    data class MoveRight(val isShift: Boolean = false) : KeyAction
    data class MoveUp(val isShift: Boolean = false) : KeyAction
    data class MoveDown(val isShift: Boolean = false) : KeyAction
    data object MoveHome : KeyAction
    data object MoveEnd : KeyAction
    data object PageUp : KeyAction
    data object PageDown : KeyAction

    // Ctrl ショートカットアクション
    data class CtrlShortcut(val key: String) : KeyAction
}
