package io.github.kachaya.karukan

/**
 * 変換候補のソース（AIモデル、ユーザー辞書、学習履歴、リライター等）によるフィルタリングおよび
 * 絵文字候補検索を行うマネージャーオブジェクトです。
 */
object CandidateFilterManager {

    /**
     * ソースフィルター ([KarukanInputMethodService.SourceFilter]) に基づき候補リストをフィルタリングします。
     */
    fun filterCandidatesBySource(
        rawCandidates: List<String>,
        activeSourceFilter: KarukanInputMethodService.SourceFilter,
        engine: KarukanEngine
    ): List<String> {
        if (activeSourceFilter == KarukanInputMethodService.SourceFilter.ALL) return rawCandidates

        val learningEntries = try {
            engine.getLearningEntries().map { it.surface }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        return when (activeSourceFilter) {
            KarukanInputMethodService.SourceFilter.LEARNING -> {
                val learningCands = rawCandidates.filter { cand ->
                    cand.contains("📝") || cand.extractPureCandidate() in learningEntries
                }
                if (learningCands.isNotEmpty()) learningCands else rawCandidates
            }
            KarukanInputMethodService.SourceFilter.SYMBOL -> {
                val rewriterCands = rawCandidates.filter { it.contains("🔄") || it.contains("📅") }
                val symbolCandList = rawCandidates.filter { cand ->
                    !cand.contains("🔄") && !cand.contains("📅") && run {
                        val pure = cand.extractPureCandidate()
                        pure.any { c ->
                            c in '0'..'9' || c in '①'..'⑨' ||
                                    c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~' ||
                                    Character.getType(c) == Character.OTHER_SYMBOL.toInt() ||
                                    Character.getType(c) == Character.SURROGATE.toInt()
                        }
                    }
                }
                val sorted = rewriterCands + symbolCandList
                if (sorted.isNotEmpty()) sorted else rawCandidates
            }
            KarukanInputMethodService.SourceFilter.AI -> rawCandidates.filter { it.extractPureCandidate() !in learningEntries }
            KarukanInputMethodService.SourceFilter.DICTIONARY -> rawCandidates.filter { it.isNotEmpty() }
            else -> rawCandidates
        }
    }

    /**
     * 絵文字検索クエリに基づき対応する絵文字候補リストを返します。
     */
    fun fetchEmojiCandidates(engine: KarukanEngine, query: String): List<String> {
        val result = mutableListOf<String>()
        val categories = engine.getEmojiCategories()
        for ((_, emojis) in categories) {
            for (e in emojis) {
                if (query.isEmpty() || e.contains(query)) {
                    result.add(e)
                }
            }
        }
        return if (result.isNotEmpty()) result.distinct().take(30) else listOf("😊", "👍", "🎉", "❤️")
    }
}
