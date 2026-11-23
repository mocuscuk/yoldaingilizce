package com.mocuscuk.yoldaingilizce.data

import kotlinx.serialization.Serializable

/** CEFR levels, easiest first. */
val LEVELS = listOf("A1", "A2", "B1", "B2", "C1")

fun levelIndex(level: String): Int = LEVELS.indexOf(level).coerceAtLeast(0)

@Serializable
data class Word(
    val w: String,
    val tr: String,
    val ex: String,
    val lv: String,
)

@Serializable
data class GrammarTopic(
    val id: String,
    val title: String,
    val tr: String,
    val lv: String,
    val tip: String,
)

@Serializable
data class GrammarItem(
    val topic: String,
    val s: String,
    val ok: Boolean,
    val fix: String = "",
    val why: String = "",
)

@Serializable
data class GrammarFile(
    val topics: List<GrammarTopic>,
    val items: List<GrammarItem>,
)

/** Leitner box for one word. due is an epoch day. */
@Serializable
data class WordState(
    val box: Int = 0,
    val due: Long = 0,
    val seen: Int = 0,
    val right: Int = 0,
)

@Serializable
data class Progress(
    val placementDone: Boolean = false,
    val level: String = "A1",
    val words: Map<String, WordState> = emptyMap(),
    val grammarTopic: Int = 0,
    val grammarStreak: Int = 0,
    val grammarMiss: Int = 0,
    val grammarAnswered: Int = 0,
    val grammarCorrect: Int = 0,
    val xp: Int = 0,
    val streakDays: Int = 0,
    val lastActiveDay: Long = -1,
    val slow: Boolean = false,
) {
    val learnedWords: Int get() = words.values.count { it.box >= 3 }
    val seenWords: Int get() = words.size
}
