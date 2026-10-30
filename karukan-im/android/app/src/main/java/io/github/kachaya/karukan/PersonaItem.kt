package io.github.kachaya.karukan

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 変換ペルソナの個別のアイテムデータを保持するデータクラス
 */
data class PersonaItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isEnabled: Boolean = true
) {
    companion object {
        fun fromJsonString(jsonStr: String): List<PersonaItem> {
            val list = mutableListOf<PersonaItem>()
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val text = obj.getString("text")
                    val isEnabled = obj.optBoolean("isEnabled", true)
                    list.add(PersonaItem(id, text, isEnabled))
                }
            } catch (_: Exception) {}
            return list
        }

        fun toJsonString(items: List<PersonaItem>): String {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("isEnabled", item.isEnabled)
                }
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }
    }
}
