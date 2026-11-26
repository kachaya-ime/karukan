package io.github.kachaya.karukan

import android.content.Context
import org.json.JSONObject

// assets/layouts/*.json から画面キーボード定義をパース・構築するユーティリティクラス
object JsonKeyboardLayoutParser {

    private val cache = mutableMapOf<String, List<List<KeySpec>>>()

    fun parseFromAsset(context: Context, assetPath: String): List<List<KeySpec>> {
        cache[assetPath]?.let { return it }

        val jsonString = try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = parse(context, jsonString)
        if (parsed.isNotEmpty()) {
            cache[assetPath] = parsed
        }
        return parsed
    }

    fun parse(context: Context, jsonString: String): List<List<KeySpec>> {
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return emptyList()
        }

        val rowsArray = root.optJSONArray("rows") ?: return emptyList()
        val result = mutableListOf<List<KeySpec>>()

        for (i in 0 until rowsArray.length()) {
            val rowArray = rowsArray.optJSONArray(i) ?: continue
            val rowSpecs = mutableListOf<KeySpec>()

            for (j in 0 until rowArray.length()) {
                val keyObj = rowArray.optJSONObject(j) ?: continue

                val normalText = keyObj.optString("normal", "")
                val shiftText = keyObj.optString("shift", if (normalText.length == 1 && normalText[0].isLowerCase()) normalText.uppercase() else normalText)
                val symbolText = keyObj.optString("symbol", normalText)
                val ctrlText = keyObj.optString("ctrl", normalText)

                val normalTypeName = keyObj.optString("normalType", keyObj.optString("action", "CHARACTER"))
                val normalType = parseKeyType(normalTypeName)

                val shiftTypeName = keyObj.optString("shiftType", normalTypeName)
                val shiftType = parseKeyType(shiftTypeName)

                val symbolTypeName = keyObj.optString("symbolType", normalTypeName)
                val symbolType = parseKeyType(symbolTypeName)

                val ctrlTypeName = keyObj.optString("ctrlType", normalTypeName)
                val ctrlType = parseKeyType(ctrlTypeName)

                val weight = keyObj.optDouble("weight", 1.0).toFloat()
                val repeatable = keyObj.optBoolean("repeatable", false)
                val textSize = keyObj.optDouble("textSize", 22.0).toFloat()

                val iconName = keyObj.optString("icon", "")
                val drawableResId = if (iconName.isNotEmpty()) {
                    context.resources.getIdentifier(iconName, "drawable", context.packageName)
                } else {
                    0
                }

                val keySpec = KeySpec(
                    normalText = normalText,
                    shiftText = shiftText,
                    symbolText = symbolText,
                    ctrlText = ctrlText,
                    normalType = normalType,
                    shiftType = shiftType,
                    symbolType = symbolType,
                    ctrlType = ctrlType,
                    weight = weight,
                    textSize = textSize,
                    repeatable = repeatable,
                    drawableResId = drawableResId
                )
                rowSpecs.add(keySpec)
            }
            result.add(rowSpecs)
        }
        return result
    }

    private fun parseKeyType(typeName: String): KeyType {
        return try {
            KeyType.valueOf(typeName.uppercase())
        } catch (e: Exception) {
            KeyType.CHARACTER
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
