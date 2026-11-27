package com.mocuscuk.yoldaingilizce.data

import android.content.Context
import com.mocuscuk.yoldaingilizce.ai.AiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lesson content: the seed lists bundled in assets, plus anything the AI
 * generated earlier (cached on disk, so it also works offline in a tunnel).
 */
class ContentRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val aiWordsFile = File(context.filesDir, "ai_words.json")
    private val aiGrammarFile = File(context.filesDir, "ai_grammar.json")

    private val seedWords: List<Word> by lazy { readAsset("words.json") }
    private val topicWords: List<Word> by lazy { readAsset("topic_words.json") }
    private val seedGrammar: GrammarFile by lazy { readAsset("grammar.json") }

    @Volatile private var aiWords: List<Word> = readFile(aiWordsFile) ?: emptyList()
    @Volatile private var aiGrammar: List<GrammarItem> = readFile(aiGrammarFile) ?: emptyList()

    val topics: List<GrammarTopic> get() = seedGrammar.topics

    /** All words, easiest level first, without duplicates. Themed words win over plain ones. */
    fun allWords(): List<Word> =
        (topicWords + aiWords.filter { it.tp.isNotEmpty() } + seedWords + aiWords.filter { it.tp.isEmpty() })
            .distinctBy { it.w.lowercase() }
            .sortedBy { levelIndex(it.lv) }

    fun wordsAt(level: String): List<Word> = allWords().filter { it.lv == level }

    fun itemsFor(topicId: String): List<GrammarItem> =
        seedGrammar.items.filter { it.topic == topicId } + aiGrammar.filter { it.topic == topicId }

    fun seedItemsAt(level: String): List<GrammarItem> {
        val ids = topics.filter { it.lv == level }.map { it.id }.toSet()
        return seedGrammar.items.filter { it.topic in ids }
    }

    /** Asks the AI for new words at this level (and theme). Returns how many were added. */
    suspend fun expandWords(ai: AiClient, level: String, count: Int = 30, topic: WordTopic? = null): Int {
        val known = allWords().map { it.w }
        val fresh = ai.words(level, count, known, topic)
            .filter { it.w.isNotBlank() && it.tr.isNotBlank() }
            .map { it.copy(lv = level, tp = topic?.id.orEmpty()) }
        val existing = known.map { it.lowercase() }.toSet()
        val added = fresh.filter { it.w.lowercase() !in existing }
        if (added.isNotEmpty()) {
            aiWords = aiWords + added
            writeFile(aiWordsFile, aiWords)
        }
        return added.size
    }

    /** Asks the AI for new true/false sentences on this topic. */
    suspend fun expandGrammar(ai: AiClient, topic: GrammarTopic, count: Int = 10): Int {
        val fresh = ai.grammar(topic, count).map { it.copy(topic = topic.id) }
        val existing = itemsFor(topic.id).map { it.s.lowercase() }.toSet()
        val added = fresh.filter { it.s.isNotBlank() && it.s.lowercase() !in existing }
        if (added.isNotEmpty()) {
            aiGrammar = aiGrammar + added
            writeFile(aiGrammarFile, aiGrammar)
        }
        return added.size
    }

    private inline fun <reified T> readAsset(name: String): T =
        context.assets.open(name).bufferedReader().use { json.decodeFromString(it.readText()) }

    private inline fun <reified T> readFile(file: File): T? =
        runCatching { if (file.exists()) json.decodeFromString<T>(file.readText()) else null }.getOrNull()

    private suspend inline fun <reified T> writeFile(file: File, value: T) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        tmp.renameTo(file)
    }
}
