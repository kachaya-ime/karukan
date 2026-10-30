package io.github.kachaya.karukan

/**
 * ニコタッチ入力（12キーマトリクス入力）のキーボードレイアウト定義、
 * 文字マッピング、および濁音/小文字等のトグル変換処理を提供するオブジェクトです。
 */
object NikoKeyboardLayout {

    /**
     * ニコタッチ 10×10 文字対応表
     * 1打目 (1〜9, 0) × 2打目 (1〜9, 0)
     *
     * 漢字ひらがな入力モード（全角大文字）オリジナルに対して括弧類を追加。
     */
    private val NIKO_TOUCH_TABLE: Map<Int, List<String>> = mapOf(
        1 to listOf("あ", "い", "う", "え", "お", ".", "-", "@", "_", "1"),
        2 to listOf("か", "き", "く", "け", "こ", "[", "a", "b", "c", "2"),
        3 to listOf("さ", "し", "す", "せ", "そ", "]", "d", "e", "f", "3"),
        4 to listOf("た", "ち", "つ", "て", "と", "っ", "g", "h", "i", "4"),
        5 to listOf("な", "に", "ぬ", "ね", "の", "(", "j", "k", "l", "5"),
        6 to listOf("は", "ひ", "ふ", "へ", "ほ", ")", "m", "n", "o", "6"),
        7 to listOf("ま", "み", "む", "め", "も", "p", "q", "r", "s", "7"),
        8 to listOf("や", "ゆ", "よ", "ゃ", "ゅ", "ょ", "t", "u", "v", "8"),
        9 to listOf("ら", "り", "る", "れ", "ろ", "w", "x", "y", "z", "9"),
        0 to listOf("わ", "を", "ん", "、", "。", "ー", "・", "！", "？", "0")
    )

    /**
     * 清音 ↔ 濁音 ↔ 半濁音 ↔ 小文字 のトグル変換マッピング定義
     */
    private val TOGGLE_MAP: Map<String, String> = mapOf(
        "あ" to "ぁ", "ぁ" to "あ",
        "い" to "ぃ", "ぃ" to "い",
        "う" to "ぅ", "ぅ" to "ゔ", "ゔ" to "う",
        "え" to "ぇ", "ぇ" to "え",
        "お" to "ぉ", "ぉ" to "お",

        "か" to "が", "が" to "か",
        "き" to "ぎ", "ぎ" to "き",
        "く" to "ぐ", "ぐ" to "く",
        "け" to "げ", "げ" to "け",
        "こ" to "ご", "ご" to "こ",

        "さ" to "ざ", "ざ" to "さ",
        "し" to "じ", "じ" to "し",
        "す" to "ず", "ず" to "す",
        "せ" to "ぜ", "ぜ" to "せ",
        "そ" to "ぞ", "ぞ" to "そ",

        "た" to "だ", "だ" to "た",
        "ち" to "ぢ", "ぢ" to "ち",
        "つ" to "っ", "っ" to "づ", "づ" to "つ",
        "て" to "で", "で" to "て",
        "と" to "ど", "ど" to "と",

        "は" to "ば", "ば" to "ぱ", "ぱ" to "は",
        "ひ" to "び", "び" to "ぴ", "ぴ" to "ひ",
        "ふ" to "ぶ", "ぶ" to "ぷ", "ぷ" to "ふ",
        "へ" to "べ", "べ" to "ぺ", "ぺ" to "へ",
        "ほ" to "ぼ", "ぼ" to "ぽ", "ぽ" to "ほ",

        "や" to "ゃ", "ゃ" to "や",
        "ゆ" to "ゅ", "ゅ" to "ゆ",
        "よ" to "ょ", "ょ" to "よ",
        "わ" to "ゎ", "ゎ" to "わ"
    )

    /**
     * 指定した1打目キー（`rowKey`: 1〜9, 0）に対応する、
     * 2打目の全10文字のリストを取得します。
     * （2打目入力待ち時のキーボードガイド表示に利用されます）
     *
     * @param rowKey 1打目のキーインデックス (1〜9, 0)
     * @return 対応する10文字のリスト（存在しない場合は null）
     */
    fun getRowGuideChars(rowKey: Int): List<String>? = NIKO_TOUCH_TABLE[rowKey]

    /**
     * Android の物理キーボード等における KeyEvent の KeyCode (`KEYCODE_0`〜`KEYCODE_9`) を、
     * ニコタッチキーインデックス (`0`〜`9`) に変換します。
     *
     * @param keyCode Android の KeyEvent キーコード
     * @return 対応するニコタッチキーインデックス (0〜9)、対象外の場合は null
     */
    fun keyCodeToNikoKeyIndex(keyCode: Int): Int? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_0 -> 0
        android.view.KeyEvent.KEYCODE_1 -> 1
        android.view.KeyEvent.KEYCODE_2 -> 2
        android.view.KeyEvent.KEYCODE_3 -> 3
        android.view.KeyEvent.KEYCODE_4 -> 4
        android.view.KeyEvent.KEYCODE_5 -> 5
        android.view.KeyEvent.KEYCODE_6 -> 6
        android.view.KeyEvent.KEYCODE_7 -> 7
        android.view.KeyEvent.KEYCODE_8 -> 8
        android.view.KeyEvent.KEYCODE_9 -> 9
        else -> null
    }

    /**
     * 指定された文字文字列が「直接入力コミット対象（英数字・記号・スペース）」かどうかを判定します。
     *
     * @param charText 判定対象の文字文字列
     * @return 直接コミット対象であれば true、それ以外は false
     */
    fun isDirectCommit(charText: String): Boolean {
        if (charText == " ") return true
        val c = charText.firstOrNull() ?: return false
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '.' || c == '-' || c == '@' || c == '_' ||
                (c in "、。・？！")
    }

    /**
     * 入力された文字の濁音・半濁音・小文字へのトグル変換を行います。
     * 変換定義が存在しない場合は元の文字をそのまま返します。
     *
     * @param input 変換対象の文字
     * @return トグル変換後の文字
     */
    fun toggleChar(input: String): String = TOGGLE_MAP[input] ?: input

    /**
     * かな/英数モード用のニコタッチ12キーレイアウト仕様（1打目 / メイン画面）を構築して返します。
     * 未入力時 (`hasInput = false`) は「区切」キーの位置に絵文字パレット呼び出しキー (`😊`) を配置します。
     * ひらがなモード時は STAR キーにサブテキストで 2段表示用 (`subText = "゛゜"`, `subText2 = "大⇔小"`) を設定します。
     *
     * @param hasInput テキストが入力中（未確定文字列あり）かどうか
     * @param isHiragana ひらがなモードかどうか
     * @return キーレイアウトの仕様（行ごとの KeySpec リスト）
     */
    fun getLayoutSpec(hasInput: Boolean = true, isHiragana: Boolean = true): List<List<KeySpec>> {
        val chunkOrEmoji = if (hasInput) {
            KeySpec("区切", KeyType.CHUNK_FIX)
        } else {
            KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24)
        }
        val starKey = if (isHiragana) {
            KeySpec(text = "", type = KeyType.KEY_STAR, subText = "゛゜", subText2 = "大⇔小")
        } else {
            KeySpec("*", KeyType.KEY_STAR)
        }
        return listOf(
            listOf(
                KeySpec("Esc", KeyType.ESC),
                KeySpec("あ", KeyType.NIKO_KEY_1, subText = "1", subText2 = "@"),
                KeySpec("か", KeyType.NIKO_KEY_2, subText = "2", subText2 = "ABC"),
                KeySpec("さ", KeyType.NIKO_KEY_3, subText = "3", subText2 = "DEF"),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("た", KeyType.NIKO_KEY_4, subText = "4", subText2 = "GHI"),
                KeySpec("な", KeyType.NIKO_KEY_5, subText = "5", subText2 = "JKL"),
                KeySpec("は", KeyType.NIKO_KEY_6, subText = "6", subText2 = "MNO"),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                chunkOrEmoji,
                KeySpec("ま", KeyType.NIKO_KEY_7, subText = "7", subText2 = "PQRS"),
                KeySpec("や", KeyType.NIKO_KEY_8, subText = "8", subText2 = "TUV"),
                KeySpec("ら", KeyType.NIKO_KEY_9, subText = "9", subText2 = "WXYZ"),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                starKey,
                KeySpec("わ", KeyType.NIKO_KEY_0, subText = "0", subText2 = "をん"),
                KeySpec("@!?", KeyType.KEY_POUND),
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }

    /**
     * 1打目キー選択後の2打目入力待ち状態（セカンドステージ）のキーレイアウト仕様を構築して返します。
     * 選択された行 (`rowKey`) に応じたガイド文字が各キーに割り当てられます。
     * ひらがなモード時は STAR キーにサブテキストで 2段表示用 (`subText = "゛゜"`, `subText2 = "大⇔小"`) を設定します。
     *
     * @param rowKey 選択された1打目の行キーインデックス (0〜9)
     * @param isHiragana ひらがなモードかどうか
     * @return セカンドステージキーレイアウトの仕様（行ごとの KeySpec リスト）
     */
    fun getSecondStageLayoutSpec(rowKey: Int, isHiragana: Boolean = true): List<List<KeySpec>> {
        val guideChars = getRowGuideChars(rowKey) ?: listOf("", "", "", "", "", "", "", "", "", "")
        val starKey = if (isHiragana) {
            KeySpec(text = "", type = KeyType.KEY_STAR, subText = "゛゜", subText2 = "大⇔小")
        } else {
            KeySpec("*", KeyType.KEY_STAR)
        }
        return listOf(
            listOf(
                KeySpec("戻る", KeyType.ESC),
                KeySpec(guideChars.getOrElse(0) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(1) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(2) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec(guideChars.getOrElse(3) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(4) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(5) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                KeySpec("区切", KeyType.CHUNK_FIX),
                KeySpec(guideChars.getOrElse(6) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(7) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec(guideChars.getOrElse(8) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                starKey,
                KeySpec(guideChars.getOrElse(9) { "" }, KeyType.NIKO_GUIDE_CHAR),
                KeySpec("@!?", KeyType.EMOJI),
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }

    /**
     * 数字専用モード用のニコタッチ12キーレイアウト仕様を構築して返します。
     * 未入力時 (`hasInput = false`) は「区切」キーの位置に絵文字パレット呼び出しキー (`😊`) を配置します。
     *
     * @param hasInput テキストが入力中（未確定文字列あり）かどうか
     * @return 数字専用キーレイアウトの仕様（行ごとの KeySpec リスト）
     */
    fun getNumericLayoutSpec(hasInput: Boolean = true): List<List<KeySpec>> {
        val chunkOrEmoji = if (hasInput) {
            KeySpec("区切", KeyType.CHUNK_FIX)
        } else {
            KeySpec("😊", KeyType.EMOJI, R.drawable.outline_emoji_emotions_24)
        }
        return listOf(
            listOf(
                KeySpec("Esc", KeyType.ESC),
                KeySpec("1", KeyType.NIKO_KEY_1),
                KeySpec("2", KeyType.NIKO_KEY_2),
                KeySpec("3", KeyType.NIKO_KEY_3),
                KeySpec("⌫", KeyType.BACKSPACE, repeatable = true)
            ),
            listOf(
                KeySpec("◀", KeyType.DPAD_LEFT, repeatable = true),
                KeySpec("4", KeyType.NIKO_KEY_4),
                KeySpec("5", KeyType.NIKO_KEY_5),
                KeySpec("6", KeyType.NIKO_KEY_6),
                KeySpec("▶", KeyType.DPAD_RIGHT, repeatable = true)
            ),
            listOf(
                chunkOrEmoji,
                KeySpec("7", KeyType.NIKO_KEY_7),
                KeySpec("8", KeyType.NIKO_KEY_8),
                KeySpec("9", KeyType.NIKO_KEY_9),
                KeySpec("⌴", KeyType.SPACE)
            ),
            listOf(
                KeySpec("あ1", KeyType.NIKO_LANG),
                KeySpec("*", KeyType.KEY_STAR),
                KeySpec("0", KeyType.NIKO_KEY_0),
                KeySpec("#", KeyType.KEY_POUND),
                KeySpec("⏎", KeyType.ENTER)
            )
        )
    }
}
