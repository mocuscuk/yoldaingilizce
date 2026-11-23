package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.data.GrammarItem
import com.mocuscuk.yoldaingilizce.data.GrammarTopic

/** Correct answers in a row needed to climb one step. */
private const val CLIMB_AFTER = 4

/** Wrong answers in a row that take you one step down. */
private const val DROP_AFTER = 2

/** Sentences already asked in this app session, to avoid repeats. */
private val recentlyAsked = ArrayDeque<String>()

/**
 * Grammar ladder. You hear a sentence and judge it:
 * Next = correct, Previous = wrong. Climbs after a streak, steps back after misses.
 */
suspend fun LessonContext.grammarRound(questions: Int = 8) {
    var topic = currentTopic()
    show("Gramer", topic.title)
    tr("Gramer merdiveni. Konu: ${topic.tr}.")
    tr(topic.tip)
    if (store.value.grammarAnswered < 10) {
        tr("Cümle doğruysa ileri, yanlışsa geri tuşuna bas.")
    }

    repeat(questions) {
        val item = nextItem(topic) ?: run {
            tr("Bu konu için soru kalmadı.")
            return
        }
        val answer = askSentence(topic, item)
        if (answer == null) {
            tr("Cevap gelmedi, geçiyorum.")
            return@repeat
        }
        val correct = (answer == Input.NEXT) == item.ok
        if (correct) {
            store.addXp(10)
            tr("Doğru!")
            if (!item.ok && item.fix.isNotBlank()) {
                tr("Düzeltilmiş hali:")
                say(item.fix, Lang.EN, 0.9f)
            }
        } else {
            store.addXp(2)
            if (item.ok) {
                tr("Maalesef, bu cümle doğruydu.")
                say(item.s, Lang.EN, 0.9f)
            } else {
                tr("Maalesef, bu cümlede hata vardı. Doğrusu:")
                say(item.fix, Lang.EN, 0.9f)
                if (item.why.isNotBlank()) tr(item.why)
            }
        }
        val moved = recordAnswer(correct)
        if (moved != null) {
            topic = currentTopic()
            show("Gramer", topic.title)
            if (moved > 0) {
                tr("Harika, bir basamak yukarı çıktın. Yeni konu: ${topic.tr}.")
            } else {
                tr("Bir basamak geri dönelim: ${topic.tr}.")
            }
            tr(topic.tip)
        }
        gap(500)
    }
    store.markActiveToday()
}

fun LessonContext.currentTopic(): GrammarTopic {
    val topics = content.topics
    return topics[store.value.grammarTopic.coerceIn(0, topics.lastIndex)]
}

private suspend fun LessonContext.askSentence(topic: GrammarTopic, item: GrammarItem): Input? {
    clearInput()
    show(topic.title, item.s)
    say(item.s, Lang.EN, 0.9f, interruptible = true)
    tr("Doğru mu?", interruptible = true)
    awaitInput(9_000)?.let { return it }
    say(item.s, Lang.EN, 0.8f, interruptible = true)
    return awaitInput(9_000)
}

/** Updates the ladder. Returns +1 / -1 when the topic changed, else null. */
private fun LessonContext.recordAnswer(correct: Boolean): Int? {
    var moved: Int? = null
    val last = content.topics.lastIndex
    store.update { p ->
        var streak = if (correct) p.grammarStreak + 1 else 0
        var miss = if (correct) 0 else p.grammarMiss + 1
        var topic = p.grammarTopic
        if (streak >= CLIMB_AFTER && topic < last) {
            topic++; streak = 0; miss = 0; moved = 1
        } else if (miss >= DROP_AFTER && topic > 0) {
            topic--; streak = 0; miss = 0; moved = -1
        }
        p.copy(
            grammarTopic = topic,
            grammarStreak = streak,
            grammarMiss = miss,
            grammarAnswered = p.grammarAnswered + 1,
            grammarCorrect = p.grammarCorrect + if (correct) 1 else 0,
        )
    }
    return moved
}

private suspend fun LessonContext.nextItem(topic: GrammarTopic): GrammarItem? {
    var fresh = content.itemsFor(topic.id).filter { it.s !in recentlyAsked }
    if (fresh.size < 3 && ai.configured) {
        quietly { content.expandGrammar(ai, topic) }
        fresh = content.itemsFor(topic.id).filter { it.s !in recentlyAsked }
    }
    val item = fresh.randomOrNull() ?: content.itemsFor(topic.id).randomOrNull() ?: return null
    recentlyAsked.addLast(item.s)
    while (recentlyAsked.size > 60) recentlyAsked.removeFirst()
    return item
}
