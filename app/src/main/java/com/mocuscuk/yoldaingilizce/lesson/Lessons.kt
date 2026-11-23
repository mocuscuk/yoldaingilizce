package com.mocuscuk.yoldaingilizce.lesson

import kotlinx.coroutines.launch

/** The things you can start from the car or the phone. */
object Lessons {
    const val JOURNEY = "journey"
    const val PLACEMENT = "placement"
    const val VOCAB = "vocab"
    const val GRAMMAR = "grammar"

    suspend fun run(id: String, ctx: LessonContext) {
        when (id) {
            PLACEMENT -> ctx.placementTest()
            VOCAB -> ctx.vocabRound(size = 25, newWords = 10)
            GRAMMAR -> ctx.grammarRound(questions = 15)
            else -> ctx.journey()
        }
    }

    /** Maps a spoken search ("Hey Google, play grammar on Yolda İngilizce") to a lesson. */
    fun fromSearch(query: String?): String {
        val q = query.orEmpty().lowercase()
        return when {
            listOf("seviye", "test", "level", "placement").any { it in q } -> PLACEMENT
            listOf("kelime", "word", "vocab").any { it in q } -> VOCAB
            listOf("gramer", "grammar", "dilbilgisi").any { it in q } -> GRAMMAR
            else -> JOURNEY
        }
    }
}

/**
 * The commute mode: placement test on the first drive, then words and
 * grammar take turns until you stop.
 */
suspend fun LessonContext.journey() {
    if (!store.value.placementDone) {
        tr("Merhaba! Önce seviyeni belirleyelim.")
        placementTest()
    } else {
        tr("Merhaba! Yolculuk modu başlıyor. ${store.value.streakDays} günlük serin var.")
    }
    var round = 0
    while (true) {
        prefetch()
        vocabRound(size = 12, newWords = 5)
        gap(800)
        grammarRound(questions = 8)
        gap(800)
        round++
        if (round % 2 == 0) {
            val p = store.value
            tr("Kısa bir özet: ${p.xp} puanın var ve ${p.learnedWords} kelimeyi öğrendin.")
        }
    }
}

/** Fetches more AI content in the background while there is signal. */
private fun LessonContext.prefetch() {
    if (!ai.configured) return
    scope.launch {
        quietly {
            levelNeedingWords()?.let { content.expandWords(ai, it) }
        }
        quietly {
            val topic = currentTopic()
            if (content.itemsFor(topic.id).size < 12) content.expandGrammar(ai, topic)
        }
    }
}
