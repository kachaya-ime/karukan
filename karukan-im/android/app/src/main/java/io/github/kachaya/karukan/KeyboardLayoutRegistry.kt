package io.github.kachaya.karukan

import java.util.Locale

/**
 * キーボードレイアウトのテーブル（レジストリ）管理クラス。
 * 各キーボードレイアウトIDに対応する KeyboardLayoutProvider を管理します。
 */
object KeyboardLayoutRegistry {

    // ---------------------------------------------------------------------------------------------
    // Region: Layout Providers Registry
    // ---------------------------------------------------------------------------------------------

    val qwertyProvider: KeyboardLayoutProvider = StandardGridKeyboardProvider(
        layoutId = "qwerty",
        rowCount = 5,
        layoutSpecSupplier = { QwertyKeyboardLayout.getLayoutSpec() }
    )

    val tabletProvider: KeyboardLayoutProvider = StandardGridKeyboardProvider(
        layoutId = "tablet",
        rowCount = 5,
        layoutSpecSupplier = { TabletKeyboardLayout.getLayoutSpec() }
    )

    val builtinProvider: KeyboardLayoutProvider = StandardGridKeyboardProvider(
        layoutId = "builtin",
        rowCount = 2,
        layoutSpecSupplier = { BuiltinKeyboardLayout.getLayoutSpec() }
    )

    val nikoProvider: KeyboardLayoutProvider = NikoKeyboardLayout

    val graffitiProvider: KeyboardLayoutProvider = GraffitiKeyboardLayout

    private val providers: Map<String, KeyboardLayoutProvider> = mapOf(
        "qwerty" to qwertyProvider,
        "tablet" to tabletProvider,
        "builtin" to builtinProvider,
        "niko" to nikoProvider,
        "graffiti" to graffitiProvider
    )

    // ---------------------------------------------------------------------------------------------
    // Region: Provider & LayoutSpec Lookup
    // ---------------------------------------------------------------------------------------------

    /**
     * 指定されたレイアウト名に対応する KeyboardLayoutProvider を取得します。
     *
     * @param layoutName レイアウト名 ("qwerty", "tablet", "niko", "builtin", "graffiti" 等)
     * @return 対応する KeyboardLayoutProvider (未定義の場合は QWERTY)
     */
    fun getProvider(layoutName: String): KeyboardLayoutProvider {
        return providers[layoutName.lowercase(Locale.getDefault())] ?: qwertyProvider
    }

    /**
     * 指定されたレイアウト名に対応する基本レイアウト仕様（List<List<KeySpec>>）を取得します。
     */
    fun getLayoutSpec(layoutName: String, isHiragana: Boolean = true, hasInput: Boolean = true): List<List<KeySpec>> {
        return when (layoutName.lowercase(Locale.getDefault())) {
            "builtin" -> BuiltinKeyboardLayout.getLayoutSpec()
            "tablet" -> TabletKeyboardLayout.getLayoutSpec()
            "niko" -> NikoKeyboardLayout.getLayoutSpec(hasInput, isHiragana)
            "qwerty" -> QwertyKeyboardLayout.getLayoutSpec()
            else -> QwertyKeyboardLayout.getLayoutSpec()
        }
    }

    /**
     * 利用可能な全標準レイアウト名のリストを返します。
     */
    val supportedLayouts: List<String> = listOf("qwerty", "tablet", "niko", "builtin", "graffiti")
}
