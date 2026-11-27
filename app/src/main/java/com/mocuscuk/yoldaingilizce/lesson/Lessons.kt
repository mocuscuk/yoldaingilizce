package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.data.ProgressStore
import com.mocuscuk.yoldaingilizce.data.wordTopic
import kotlinx.coroutines.launch

/** The things you can start from the car or the phone. */
object Lessons {
    const val JOURNEY = "journey"
    const val PLACEMENT = "placement"
    const val VOCAB = "vocab"
    const val GRAMMAR = "grammar"
    const val CHAT = "chat"
    const val SUMMARY = "summary"

    /** "topic:travel" sets the word theme and starts the journey. "topic:all" clears it. */
    const val TOPIC_PREFIX = "topic:"
    const val TOPIC_ALL = "all"

    suspend fun run(id: String, ctx: LessonContext) {
        try {
            when {
                id == SUMMARY -> ctx.tripSummary()
                id.startsWith(TOPIC_PREFIX) -> {
                    ctx.pickTopic(id.removePrefix(TOPIC_PREFIX))
                    ctx.journey(greet = false)
                }
                id == PLACEMENT -> ctx.placementTest()
                id == VOCAB -> ctx.vocabRound(size = 25, newWords = 10)
                id == GRAMMAR -> ctx.grammarRound(questions = 15)
                id == CHAT -> if (!ctx.chat()) ctx.journey(greet = false)
                else -> ctx.journey()
            }
        } catch (e: EndTrip) {
            ctx.clearEnd()
            ctx.tripSummary()
        }
    }

    /** Maps a spoken search ("Hey Google, play grammar on Yolda İngilizce") to a lesson. */
    fun fromSearch(query: String?): String {
        val q = " " + query.orEmpty().lowercase(java.util.Locale.forLanguageTag("tr-TR")) + " "
        return when {
            listOf("seyahat", "travel", "tatil").any { it in q } -> TOPIC_PREFIX + "travel"
            listOf("iş ingilizce", "business", "ofis", " iş ").any { it in q } -> TOPIC_PREFIX + "business"
            listOf("günlük", "daily").any { it in q } -> TOPIC_PREFIX + "daily"
            listOf("karışık", "mixed").any { it in q } -> TOPIC_PREFIX + TOPIC_ALL
            listOf("sohbet", "konuş", "chat", "conversation", "talk").any { it in q } -> CHAT
            listOf("özet", "summary", "bitir").any { it in q } -> SUMMARY
            listOf("seviye", "test", "level", "placement").any { it in q } -> PLACEMENT
            listOf("kelime", "word", "vocab").any { it in q } -> VOCAB
            listOf("gramer", "grammar", "dilbilgisi").any { it in q } -> GRAMMAR
            else -> JOURNEY
        }
    }
}

/** Sets the word theme and says it. */
suspend fun LessonContext.pickTopic(id: String) {
    val topic = wordTopic(id)
    store.update { it.copy(wordTopic = topic?.id.orEmpty()) }
    if (topic == null) {
        show("Konu", "Karışık")
        tr("Konu seçimi kaldırıldı. Kelimeler karışık gelecek.")
    } else {
        show("Konu", topic.tr)
        tr("Konu: ${topic.tr}. Yeni kelimeler bu konudan gelecek.")
    }
}

/**
 * The commute mode: placement test on the first drive, then words and
 * grammar take turns until you stop.
 */
suspend fun LessonContext.journey(greet: Boolean = true) {
    if (!store.value.placementDone) {
        tr("Merhaba! Önce seviyeni belirleyelim.")
        placementTest()
    } else if (greet) {
        tr("Merhaba! Yolculuk modu başlıyor. ${store.value.streakDays} günlük serin var.")
        lastTripRecap()
        wordTopic(store.value.wordTopic)?.let { tr("Kelime konusu: ${it.tr}.") }
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
            if (round == 2) tr("Yolculuk bitince bitir de, ya da ekrandaki bitir tuşuna bas, özetini söyleyeyim.")
        }
    }
}

/** When the last drive ended without its summary (car turned off), say it briefly now. */
private suspend fun LessonContext.lastTripRecap() {
    val last = store.value.lastTrip ?: return
    if (last.spoken || last.empty || ProgressStore.today() - last.day > 2) return
    tr("Geçen yolculuktan kısa bir not.")
    tripLines(last).take(2).forEach { tr(it.replace("Bu yolculukta", "Geçen yolculukta")) }
    store.update { it.copy(lastTrip = last.copy(spoken = true)) }
}

/** Fetches more AI content in the background while there is signal. */
private fun LessonContext.prefetch() {
    if (!ai.configured) return
    scope.launch {
        quietly {
            val p = store.value
            val topic = wordTopic(p.wordTopic)
            if (topic != null && topicNeedsWords()) {
                content.expandWords(ai, p.level, count = 25, topic = topic)
            } else {
                levelNeedingWords()?.let { content.expandWords(ai, it) }
            }
        }
        quietly {
            val topic = currentTopic()
            if (content.itemsFor(topic.id).size < 12) content.expandGrammar(ai, topic)
        }
    }
}
