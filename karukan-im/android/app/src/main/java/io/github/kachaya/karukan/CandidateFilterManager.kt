package io.github.kachaya.karukan

/**
 * 絵文字候補検索等を行うマネージャーオブジェクトです。
 */
object CandidateFilterManager {

    /**
     * 絵文字検索クエリに基づき対応する絵文字候補リストを返します。
     */
    fun fetchEmojiCandidates(engine: KarukanEngine, query: String): List<String> {
        val categories = engine.getEmojiCategories()
        if (categories.isEmpty()) {
            return listOf("😊", "👍", "🎉", "❤️")
        }

        val result = LinkedHashSet<String>()
        val isEmptyQuery = query.isEmpty()

        for ((_, emojis) in categories) {
            for (e in emojis) {
                if (isEmptyQuery || e.contains(query)) {
                    result.add(e)
                    if (result.size >= 30) break
                }
            }
            if (result.size >= 30) break
        }

        return if (result.isNotEmpty()) result.toList() else listOf("😊", "👍", "🎉", "❤️")
    }
}
