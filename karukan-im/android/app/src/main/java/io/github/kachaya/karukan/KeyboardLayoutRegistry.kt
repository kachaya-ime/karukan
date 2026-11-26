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
        layoutSpecSupplier = { context -> loadJsonLayoutSpec(context, "qwerty") }
    )

    val tabletProvider: KeyboardLayoutProvider = StandardGridKeyboardProvider(
        layoutId = "tablet",
        layoutSpecSupplier = { context -> loadJsonLayoutSpec(context, "tablet") }
    )

    val builtinProvider: KeyboardLayoutProvider = StandardGridKeyboardProvider(
        layoutId = "builtin",
        layoutSpecSupplier = { context -> loadJsonLayoutSpec(context, "builtin") }
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
     * JSON ファイルから指定されたレイアウト仕様を読み込みます。
     */
    fun loadJsonLayoutSpec(context: android.content.Context, layoutName: String): List<List<KeySpec>> {
        return JsonKeyboardLayoutParser.parseFromAsset(context, "layouts/${layoutName}_layout.json")
    }

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
    fun getLayoutSpec(layoutName: String, isHiragana: Boolean = true, hasInput: Boolean = true, context: android.content.Context? = null): List<List<KeySpec>> {
        val normalized = layoutName.lowercase(Locale.getDefault())
        if (normalized == "niko") {
            return NikoKeyboardLayout.getLayoutSpec(hasInput, isHiragana)
        }
        if (context != null) {
            val targetName = if (normalized in listOf("qwerty", "tablet", "builtin")) normalized else "qwerty"
            return loadJsonLayoutSpec(context, targetName)
        }
        return emptyList()
    }

    /**
     * 利用可能な全標準レイアウト名のリストを返します。
     */
    val supportedLayouts: List<String> = listOf("qwerty", "tablet", "niko", "builtin", "graffiti")
}
