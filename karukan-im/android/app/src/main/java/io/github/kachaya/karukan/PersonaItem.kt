package io.github.kachaya.karukan

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 変換ペルソナの個別のアイテムデータを保持するデータクラス（排他選択）
 */
data class PersonaItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isSelected: Boolean = false
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
                    val isSelected = if (obj.has("isSelected")) {
                        obj.getBoolean("isSelected")
                    } else {
                        obj.optBoolean("isEnabled", false)
                    }
                    list.add(PersonaItem(id, text, isSelected))
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
                    put("isSelected", item.isSelected)
                }
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }
    }
}
