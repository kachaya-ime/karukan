package io.github.kachaya.karukan

import org.json.JSONArray
import org.json.JSONObject

/**
 * ユーザー定義のキーショートカット設定項目を表すデータモデル
 */
data class KeyShortcutItem(
    val shortcutKey: String,     // キー識別子 ("a", "b", "k", "space_ctrl", "space_shift")
    val displayKeyCombo: String, // 画面表示用 ("Ctrl + A", "Ctrl + B", "Ctrl + Space" 等)
    val actionId: String,        // 割当アクションID ("select_all", "cursor_left", "katakana", "none" 等)
    val isReserved: Boolean = false // Androidシステム標準予約ショートカット (変更不可)
) {

    // ---------------------------------------------------------------------------------------------
    // Region: Default Shortcuts & JSON Serialization
    // ---------------------------------------------------------------------------------------------

    companion object {
        fun getDefaultList(): List<KeyShortcutItem> = listOf(
            KeyShortcutItem("a", "Ctrl + A", "select_all", isReserved = true),
            KeyShortcutItem("b", "Ctrl + B", "cursor_left", isReserved = true),
            KeyShortcutItem("c", "Ctrl + C", "copy", isReserved = true),
            KeyShortcutItem("d", "Ctrl + D", "none"),
            KeyShortcutItem("e", "Ctrl + E", "toggle_emoji_palette"),
            KeyShortcutItem("f", "Ctrl + F", "cursor_right", isReserved = true),
            KeyShortcutItem("g", "Ctrl + G", "escape"),
            KeyShortcutItem("h", "Ctrl + H", "backspace"),
            KeyShortcutItem("i", "Ctrl + I", "ai_candidates"),
            KeyShortcutItem("j", "Ctrl + J", "fix_chunk"),
            KeyShortcutItem("k", "Ctrl + K", "katakana"),
            KeyShortcutItem("l", "Ctrl + L", "toggle_live_conversion"),
            KeyShortcutItem("m", "Ctrl + M", "commit"),
            KeyShortcutItem("n", "Ctrl + N", "next_candidate", isReserved = true),
            KeyShortcutItem("o", "Ctrl + O", "none"),
            KeyShortcutItem("p", "Ctrl + P", "prev_candidate", isReserved = true),
            KeyShortcutItem("q", "Ctrl + Q", "none"),
            KeyShortcutItem("r", "Ctrl + R", "symbol_view"),
            KeyShortcutItem("s", "Ctrl + S", "none"),
            KeyShortcutItem("t", "Ctrl + T", "learning_view"),
            KeyShortcutItem("u", "Ctrl + U", "none"),
            KeyShortcutItem("v", "Ctrl + V", "paste", isReserved = true),
            KeyShortcutItem("w", "Ctrl + W", "none"),
            KeyShortcutItem("x", "Ctrl + X", "cut", isReserved = true),
            KeyShortcutItem("y", "Ctrl + Y", "none"),
            KeyShortcutItem("z", "Ctrl + Z", "undo", isReserved = true),
            KeyShortcutItem("space_ctrl", "Ctrl + Space", "toggle_language_mode", isReserved = true),
            KeyShortcutItem("space_shift", "Shift + Space", "full_width_space")
        )

        fun toJsonString(items: List<KeyShortcutItem>): String {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("key", item.shortcutKey)
                obj.put("action", item.actionId)
                array.put(obj)
            }
            return array.toString()
        }

        fun fromJsonString(json: String?): List<KeyShortcutItem> {
            val defaults = getDefaultList()
            if (json.isNullOrBlank()) return defaults

            return try {
                val array = JSONArray(json)
                val map = mutableMapOf<String, String>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    map[obj.getString("key")] = obj.getString("action")
                }
                defaults.map { item ->
                    if (item.isReserved) {
                        item
                    } else {
                        val customAction = map[item.shortcutKey]
                        if (customAction != null) item.copy(actionId = customAction) else item
                    }
                }
            } catch (_: Exception) {
                defaults
            }
        }

        // -----------------------------------------------------------------------------------------
        // Region: Action ID & Label Registry
        // -----------------------------------------------------------------------------------------

        fun getActionLabel(actionId: String): String = when (actionId) {
            "none" -> "未割当 (OS/アプリへ委譲)"
            "select_all" -> "全選択"
            "copy" -> "コピー"
            "paste" -> "貼り付け"
            "cut" -> "切り取り"
            "undo" -> "元に戻す / Undo"
            "katakana" -> "全角カタカナ変換 / モード切替"
            "hiragana" -> "ひらがな変換"
            "hankaku_katakana" -> "半角カタカナ変換"
            "zenkaku_alpha" -> "全角英数変換"
            "hankaku_alpha" -> "半角英数変換"
            "ai_candidates" -> "🤖 AI 候補ビュー表示"
            "fix_chunk" -> "Chunk区切り・固定"
            "symbol_view" -> "🔄 記号・単漢字ビュー表示"
            "learning_view" -> "📝 学習ソースビュー表示"
            "reconvert" -> "確定取り消し・再変換"
            "toggle_live_conversion" -> "ライブ変換トグル"
            "toggle_emoji_palette" -> "🎨 絵文字パレットの開閉"
            "toggle_detail" -> "AI推論詳細表示トグル"
            "toggle_candidate_expand" -> "候補一覧展開/折りたたみ"
            "toggle_language_mode" -> "モード切替"
            "cursor_left" -> "カーソル左移動"
            "cursor_right" -> "カーソル右移動"
            "commit" -> "確定"
            "escape" -> "キャンセル"
            "backspace" -> "前の1文字削除 (Backspace)"
            "delete_forward" -> "後方の1文字削除 (Delete)"
            "next_candidate" -> "次候補"
            "prev_candidate" -> "前候補"
            "full_width_space" -> "全角スペース / 前候補"
            else -> "未割当 (OS/アプリへ委譲)"
        }

        val configurableActionIds = listOf(
            "none",
            "toggle_emoji_palette",
            "katakana",
            "hiragana",
            "hankaku_katakana",
            "zenkaku_alpha",
            "hankaku_alpha",
            "ai_candidates",
            "fix_chunk",
            "symbol_view",
            "learning_view",
            "reconvert",
            "toggle_live_conversion",
            "toggle_detail",
            "toggle_candidate_expand",
            "toggle_language_mode",
            "cursor_left",
            "cursor_right",
            "commit",
            "escape",
            "backspace",
            "delete_forward",
            "next_candidate",
            "prev_candidate",
            "full_width_space"
        )
    }
}
