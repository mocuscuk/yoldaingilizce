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
    /** Theme id from [WORD_TOPICS], empty for general words. */
    val tp: String = "",
)

/** A word theme the learner can pick. */
data class WordTopic(val id: String, val tr: String, val en: String)

val WORD_TOPICS = listOf(
    WordTopic("business", "İş İngilizcesi", "work, office and business"),
    WordTopic("travel", "Seyahat", "travel, hotels, airports and getting around"),
    WordTopic("daily", "Günlük konuşma", "everyday conversation, shopping, home and friends"),
)

fun wordTopic(id: String): WordTopic? = WORD_TOPICS.firstOrNull { it.id == id }

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
    /** Answer by voice ("biliyorum", "bilmiyorum", "tekrar") as well as buttons. */
    val voice: Boolean = true,
    val voiceHintDone: Boolean = false,
    /** Word theme id, empty means mixed. */
    val wordTopic: String = "",
    /** The last finished trip, shown on the phone. */
    val lastTrip: TripSummary? = null,
) {
    val learnedWords: Int get() = words.values.count { it.box >= 3 }
    val seenWords: Int get() = words.size
}

/** What happened during one drive. */
@Serializable
data class TripSummary(
    val day: Long = 0,
    val minutes: Int = 0,
    val words: Int = 0,
    val known: Int = 0,
    val newWords: Int = 0,
    val grammar: Int = 0,
    val grammarRight: Int = 0,
    val chatTurns: Int = 0,
    val xp: Int = 0,
    /** False when the trip ended without the spoken summary (e.g. the car was turned off). */
    val spoken: Boolean = false,
) {
    val empty: Boolean get() = words == 0 && grammar == 0 && chatTurns == 0
}
