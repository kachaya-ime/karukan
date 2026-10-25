package io.github.kachaya.karukan

import java.util.Calendar
import java.util.Locale

/**
 * スペースや日付の補助候補に関するテキスト変換ユーティリティ
 */
object InputTransformUtils {

    fun getSpaceString(spaceStyle: String): String = if (spaceStyle == "full") "　" else " "

    fun getDateCandidates(reading: String, isEnabled: Boolean): List<String> {
        if (!isEnabled) return emptyList()

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val minuteStr = String.format(Locale.getDefault(), "%02d", minute)
        val hourStr = String.format(Locale.getDefault(), "%02d", hour)

        return when (reading) {
            "きょう" -> listOf(
                "$year/$month/$day",
                "${year}年${month}月${day}日",
                "$month/$day"
            )

            "きのう" -> {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_MONTH, -1)
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                listOf("$y/$m/$d", "${y}年${m}月${d}日")
            }

            "あした", "あす" -> {
                val cal = now.clone() as Calendar
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val y = cal.get(Calendar.YEAR)
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                listOf("$y/$m/$d", "${y}年${m}月${d}日")
            }

            "いま" -> listOf(
                "$hourStr:$minuteStr",
                "${hour}時${minute}分"
            )

            "にちじ" -> listOf(
                "$year/$month/$day $hourStr:$minuteStr",
                "${year}年${month}月${day}日 ${hour}時${minute}分"
            )

            else -> emptyList()
        }
    }
}
