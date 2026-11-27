package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.data.LEVELS
import kotlin.math.roundToInt
import kotlin.random.Random

private const val QUESTIONS = 24

/**
 * About ten minutes of adaptive true/false questions, alternating words and grammar.
 * Two right answers in a row move one level up, a wrong answer one level down.
 * The result sets where the word list and the grammar ladder start.
 */
suspend fun LessonContext.placementTest() {
    show("Seviye tespiti", "Yaklaşık 10 dakika")
    tr("Seviye tespit testi. Yaklaşık on dakika sürecek.")
    tr("Bir kelime ve anlamını, ya da bir cümle duyacaksın. Doğruysa ileri, yanlışsa geri tuşuna bas.")
    if (voiceOn) tr("Tuşlar yerine doğru ya da yanlış da diyebilirsin. Tekrar dersen soruyu yeniden söylerim.")
    tr("Emin değilsen bir şey yapma, o soru yanlış sayılır. Başlıyoruz.")

    var level = 1 // start at A2
    var streak = 0
    val visited = mutableListOf<Int>()
    val usedWords = mutableSetOf<String>()
    val usedSentences = mutableSetOf<String>()

    for (i in 0 until QUESTIONS) {
        visited.add(level)
        show("Seviye tespiti", "Soru ${i + 1} / $QUESTIONS")
        val correct = if (i % 2 == 0) {
            wordQuestion(LEVELS[level], usedWords)
        } else {
            sentenceQuestion(LEVELS[level], usedSentences)
        } ?: continue
        if (correct) {
            streak++
            if (streak >= 2) {
                level = (level + 1).coerceAtMost(LEVELS.lastIndex)
                streak = 0
            }
        } else {
            level = (level - 1).coerceAtLeast(0)
            streak = 0
        }
        gap(300)
    }

    val tail = visited.takeLast(12)
    val result = tail.average().roundToInt().coerceIn(0, LEVELS.lastIndex)
    val name = LEVELS[result]
    val topicIndex = content.topics.indexOfFirst { it.lv == name }.coerceAtLeast(0)
    store.update {
        it.copy(
            placementDone = true,
            level = name,
            grammarTopic = topicIndex,
            grammarStreak = 0,
            grammarMiss = 0,
        )
    }
    store.addXp(50)
    store.markActiveToday()
    show("Seviyen: $name", "Dersler bu seviyeden başlayacak")
    tr("Test bitti. Seviyen $name. Kelimeler ve gramer bu seviyeden başlayacak.")
}

/** Returns true/false for the answer, or null when there was nothing to ask. */
private suspend fun LessonContext.wordQuestion(level: String, used: MutableSet<String>): Boolean? {
    val pool = content.wordsAt(level).filter { it.w !in used }
    val word = pool.randomOrNull() ?: return null
    used.add(word.w)
    val truthful = Random.nextBoolean()
    val meaning = if (truthful) {
        word.tr
    } else {
        content.wordsAt(level).filter { it.w != word.w }.randomOrNull()?.tr ?: word.tr
    }
    val isTrue = meaning == word.tr
    clearInput()
    show(word.w, "= $meaning ?")
    say(word.w, Lang.EN, 0.9f)
    gap(300)
    tr("Anlamı: $meaning. Doğru mu?", interruptible = true)
    val answer = awaitAnswer(10_000) {
        say(word.w, Lang.EN, 0.8f)
        tr("Anlamı: $meaning. Doğru mu?")
    } ?: return false
    return (answer == Input.NEXT) == isTrue
}

private suspend fun LessonContext.sentenceQuestion(level: String, used: MutableSet<String>): Boolean? {
    val item = content.seedItemsAt(level).filter { it.s !in used }.randomOrNull() ?: return null
    used.add(item.s)
    clearInput()
    show("Doğru mu?", item.s)
    say(item.s, Lang.EN, 0.9f, interruptible = true)
    tr("Doğru mu?", interruptible = true)
    val answer = awaitAnswer(10_000) {
        say(item.s, Lang.EN, 0.8f)
        tr("Doğru mu?")
    } ?: return false
    return (answer == Input.NEXT) == item.ok
}
