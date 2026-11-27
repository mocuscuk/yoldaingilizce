package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.data.LEVELS
import com.mocuscuk.yoldaingilizce.data.ProgressStore
import com.mocuscuk.yoldaingilizce.data.Word
import com.mocuscuk.yoldaingilizce.data.WordState
import com.mocuscuk.yoldaingilizce.data.levelIndex

/** Days until the next review, by Leitner box. */
private val INTERVALS = longArrayOf(0, 1, 3, 7, 14, 30, 60)

/**
 * Vocabulary round with spaced repetition.
 * Next = "I knew it", Previous = "I didn't know it". Silence = not sure.
 */
suspend fun LessonContext.vocabRound(size: Int = 15, newWords: Int = 6) {
    val queue = buildQueue(size, newWords).toMutableList()
    if (queue.isEmpty()) {
        tr("Şu an tekrar edilecek kelime yok.")
        return
    }
    val total = queue.size
    show("Kelime turu", "$total kelime")
    tr("Kelime turu. ${queue.size} kelime.")
    if (store.value.seenWords < 20) {
        tr("Kelimeyi biliyorsan ileri, bilmiyorsan geri tuşuna bas.")
    }
    if (voiceOn && !store.value.voiceHintDone) {
        tr("İstersen konuşarak da cevap verebilirsin. Biliyorum, bilmiyorum ya da tekrar de.")
        store.update { it.copy(voiceHintDone = true) }
    }

    var known = 0
    var index = 0
    val retried = mutableSetOf<String>()
    while (index < queue.size) {
        val word = queue[index]
        index++
        val isNew = word.w !in store.value.words
        val answer = askWord(word, promptHint = index <= 3)
        trip.word(isNew = isNew, knew = when (answer) { Input.NEXT -> true; Input.PREV -> false; else -> null })
        when (answer) {
            Input.NEXT -> {
                known++
                grade(word, knew = true)
                store.addXp(5)
            }
            Input.PREV -> {
                grade(word, knew = false)
                store.addXp(1)
                // Bring it back a few words later, once.
                if (retried.add(word.w)) queue.add(minOf(queue.size, index + 4), word)
            }
            Input.REPEAT, null -> grade(word, knew = null)
        }
    }
    store.markActiveToday()
    tr("Kelime turu bitti. $total kelimenin $known tanesini bildin.")
}

private suspend fun LessonContext.askWord(word: Word, promptHint: Boolean): Input? {
    clearInput()
    show(word.w, "…")
    say(word.w, Lang.EN, factor = 0.9f)
    gap(700)
    say(word.w, Lang.EN, factor = 0.8f, interruptible = true)
    // Thinking time. An early answer counts; "tekrar" says the word once more.
    var early = awaitInput(3_500)
    if (early == Input.REPEAT) {
        clearInput()
        say(word.w, Lang.EN, factor = 0.7f)
        early = awaitInput(3_500).takeIf { it != Input.REPEAT }
    }
    show(word.w, word.tr)
    tr(word.tr)
    if (early == Input.NEXT) {
        gap(300)
        return early
    }
    gap(300)
    say(word.ex, Lang.EN, factor = 0.9f, interruptible = early == null)
    if (early != null) return early
    val hint = when {
        !promptHint -> "Biliyor muydun?"
        voiceOn -> "Biliyor muydun? Biliyorum ya da bilmiyorum de."
        else -> "Biliyor muydun? Evetse ileri, hayırsa geri."
    }
    tr(hint, interruptible = true)
    return awaitAnswer(6_000) {
        say(word.w, Lang.EN, factor = 0.8f)
        tr(word.tr)
        say(word.ex, Lang.EN, factor = 0.8f)
        tr("Biliyor muydun?")
    }
}

private fun LessonContext.grade(word: Word, knew: Boolean?) {
    val today = ProgressStore.today()
    store.update { p ->
        val old = p.words[word.w] ?: WordState()
        val box = when (knew) {
            true -> (old.box + 1).coerceAtMost(INTERVALS.size - 1)
            false -> 0
            null -> old.box
        }
        val due = today + if (knew == null) 1 else INTERVALS[box]
        val state = old.copy(
            box = box,
            due = due,
            seen = old.seen + 1,
            right = old.right + if (knew == true) 1 else 0,
        )
        p.copy(words = p.words + (word.w to state))
    }
}

/**
 * Due reviews first, then new words from the learner's level upwards.
 * With a theme picked, new words come from that theme and its reviews go first.
 */
private fun LessonContext.buildQueue(size: Int, newWords: Int): List<Word> {
    val p = store.value
    val today = ProgressStore.today()
    val all = content.allWords()
    val byWord = all.associateBy { it.w }
    val topic = p.wordTopic
    val due = p.words.entries
        .filter { it.value.due <= today }
        .sortedWith(compareBy({ it.value.box }, { it.value.due }))
        .mapNotNull { byWord[it.key] }
        .sortedBy { if (topic.isNotEmpty() && it.tp == topic) 0 else 1 }
    val fresh = freshWords()
    val reviews = due.take(size - minOf(newWords, fresh.size))
    val news = fresh.take(size - reviews.size)
    // Mix: a new word after every two reviews.
    val out = mutableListOf<Word>()
    val r = reviews.iterator()
    val n = news.iterator()
    while (r.hasNext() || n.hasNext()) {
        repeat(2) { if (r.hasNext()) out.add(r.next()) }
        if (n.hasNext()) out.add(n.next())
    }
    return out
}

/** Unseen words to teach next, in order. */
private fun LessonContext.freshWords(): List<Word> {
    val p = store.value
    val start = levelIndex(p.level)
    val unseen = content.allWords().filter { it.w !in p.words }
    if (p.wordTopic.isNotEmpty()) {
        val themed = unseen.filter { it.tp == p.wordTopic }
            // The learner's level and above first, easier theme words after.
            .sortedBy { if (levelIndex(it.lv) >= start) levelIndex(it.lv) - start else 10 + start - levelIndex(it.lv) }
        if (themed.isNotEmpty()) return themed
    }
    return unseen.filter { levelIndex(it.lv) >= start }
}

/** How many unseen words are left from the learner's level upwards. */
fun LessonContext.unseenWordsAhead(): Int = freshWords().size

/** The lowest level that still has fewer than [min] unseen words. */
fun LessonContext.levelNeedingWords(min: Int = 20): String? {
    val p = store.value
    for (i in levelIndex(p.level) until LEVELS.size) {
        val level = LEVELS[i]
        if (content.wordsAt(level).count { it.w !in p.words } < min) return level
    }
    return null
}

/** True when the picked theme is running low on new words. */
fun LessonContext.topicNeedsWords(min: Int = 15): Boolean {
    val p = store.value
    if (p.wordTopic.isEmpty()) return false
    return content.allWords().count { it.tp == p.wordTopic && it.w !in p.words } < min
}
