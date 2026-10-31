package io.github.kachaya.karukan

import java.util.Locale

/**
 * キーボードレイアウトのテーブル（レジストリ）管理クラス。
 * 将来的なレイアウトのカスタマイズ、保存、エクスポート・インポート（JSON/TOML等）を見据え、
 * レイアウト名とレイアウト定義（KeySpec の 2次元テーブル）をデータ駆動で管理します。
 */
object KeyboardLayoutRegistry {

    /**
     * 指定されたレイアウト名に対応する基本レイアウト仕様（List<List<KeySpec>>）を取得します。
     *
     * @param layoutName レイアウト名 ("qwerty", "tablet", "niko" 等)
     * @param isHiragana ひらがなモードかどうか (ニコタッチ用)
     * @param hasInput 入力中かどうか (ニコタッチ用)
     * @return レイアウトの2次元仕様リスト
     */
    fun getLayoutSpec(layoutName: String, isHiragana: Boolean = true, hasInput: Boolean = true): List<List<KeySpec>> {
        return when (layoutName.lowercase(Locale.getDefault())) {
            "tablet" -> TabletKeyboardLayout.getLayoutSpec()
            "niko" -> NikoKeyboardLayout.getLayoutSpec(hasInput, isHiragana)
            "qwerty" -> QwertyKeyboardLayout.getLayoutSpec()
            else -> QwertyKeyboardLayout.getLayoutSpec()
        }
    }

    /**
     * 利用可能な全標準レイアウト名のリストを返します。
     */
    val supportedLayouts: List<String> = listOf("qwerty", "tablet", "niko")
}
