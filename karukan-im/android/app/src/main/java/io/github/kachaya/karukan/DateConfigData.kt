package io.github.kachaya.karukan

import org.json.JSONArray
import org.json.JSONObject

/**
 * 他のフロントエンド（fcitx5 / macOS）と互換性のある日付・時刻変換ルールデータ構造
 */

// -------------------------------------------------------------------------------------------------
// Region: DatePhraseItem Data Model
// -------------------------------------------------------------------------------------------------

data class DatePhraseItem(
    val reading: String,
    val offsetDays: Long = 0,
    val formats: List<String>? = null
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("reading", reading)
        obj.put("offset_days", offsetDays)
        if (formats != null) {
            val arr = JSONArray()
            formats.forEach { arr.put(it) }
            obj.put("formats", arr)
        }
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): DatePhraseItem {
            val reading = obj.optString("reading", "")
            val offsetDays = obj.optLong("offset_days", 0)
            val formatsArr = obj.optJSONArray("formats")
            val formatsList = if (formatsArr != null) {
                val list = mutableListOf<String>()
                for (i in 0 until formatsArr.length()) {
                    list.add(formatsArr.getString(i))
                }
                list
            } else null
            return DatePhraseItem(reading, offsetDays, formatsList)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Region: DateConfigData Data Model & Defaults
// -------------------------------------------------------------------------------------------------

data class DateConfigData(
    val formats: List<String> = DEFAULT_FORMATS,
    val phrases: List<DatePhraseItem> = DEFAULT_PHRASES
) {
    fun toJsonString(): String {
        val root = JSONObject()
        val formatsArr = JSONArray()
        formats.forEach { formatsArr.put(it) }
        root.put("formats", formatsArr)

        val phrasesArr = JSONArray()
        phrases.forEach { phrasesArr.put(it.toJsonObject()) }
        root.put("phrases", phrasesArr)

        return root.toString()
    }

    companion object {
        val DEFAULT_FORMATS = listOf(
            "{YEAR}/{MONTH}/{DATE}",
            "{YEAR}-{MONTH}-{DATE}",
            "{YEAR}年{MONTH:bare}月{DATE:bare}日",
            "{ERA}{ERA_YEAR}年{MONTH:bare}月{DATE:bare}日",
            "{MONTH:bare}月{DATE:bare}日",
            "{MONTH:bare}月{DATE:bare}日({WEEKDAY})",
            "{WEEKDAY}曜日"
        )

        val DEFAULT_PHRASES = listOf(
            DatePhraseItem("きょう", 0),
            DatePhraseItem("きのう", -1),
            DatePhraseItem("おととい", -2),
            DatePhraseItem("あした", 1),
            DatePhraseItem("あす", 1),
            DatePhraseItem("あさって", 2),
            DatePhraseItem("しあさって", 3),
            DatePhraseItem("いま", 0, listOf("{HOUR}:{MINUTE}", "{HOUR:bare}時{MINUTE:bare}分", "{AMPM}{HOUR12}時{MINUTE:bare}分")),
            DatePhraseItem("にちじ", 0, listOf("{YEAR}/{MONTH}/{DATE} {HOUR}:{MINUTE}", "{YEAR}年{MONTH:bare}月{DATE:bare}日{HOUR:bare}時{MINUTE:bare}分"))
        )

        fun fromJsonString(jsonStr: String?): DateConfigData {
            if (jsonStr.isNullOrEmpty()) {
                return DateConfigData()
            }
            return try {
                val root = JSONObject(jsonStr)
                val formatsArr = root.optJSONArray("formats")
                val formatsList = if (formatsArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until formatsArr.length()) {
                        list.add(formatsArr.getString(i))
                    }
                    list
                } else DEFAULT_FORMATS

                val phrasesArr = root.optJSONArray("phrases")
                val phrasesList = if (phrasesArr != null) {
                    val list = mutableListOf<DatePhraseItem>()
                    for (i in 0 until phrasesArr.length()) {
                        list.add(DatePhraseItem.fromJsonObject(phrasesArr.getJSONObject(i)))
                    }
                    list
                } else DEFAULT_PHRASES

                DateConfigData(formatsList, phrasesList)
            } catch (_: Exception) {
                DateConfigData()
            }
        }
    }
}
