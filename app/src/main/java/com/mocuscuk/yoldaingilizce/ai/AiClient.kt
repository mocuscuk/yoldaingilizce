package com.mocuscuk.yoldaingilizce.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.mocuscuk.yoldaingilizce.data.GrammarItem
import com.mocuscuk.yoldaingilizce.data.GrammarTopic
import com.mocuscuk.yoldaingilizce.data.Word
import com.mocuscuk.yoldaingilizce.data.WordTopic
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talks to the "tutor" Cloud Function on the user's own Firebase project.
 * The Claude API key lives only in Firebase, never in the app.
 */
class AiClient(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    /** False when the APK was built without google-services.json. */
    val configured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    /** Firebase user id of this phone. It goes into ALLOWED_UIDS on the server. */
    suspend fun uid(): String? = runCatching { if (configured) signIn() else null }.getOrNull()

    suspend fun words(level: String, count: Int, exclude: List<String>, topic: WordTopic? = null): List<Word> {
        val text = call(
            mapOf(
                "task" to "words",
                "level" to level,
                "count" to count,
                "exclude" to exclude.takeLast(1500),
                "topic" to (topic?.en ?: ""),
            )
        )
        return json.decodeFromString<WordBatch>(text).words
    }

    suspend fun grammar(topic: GrammarTopic, count: Int): List<GrammarItem> {
        val text = call(
            mapOf(
                "task" to "grammar",
                "topic" to topic.title,
                "topicTr" to topic.tr,
                "level" to topic.lv,
                "count" to count,
            )
        )
        return json.decodeFromString<GrammarBatch>(text).items.map {
            GrammarItem(topic = topic.id, s = it.s, ok = it.ok, fix = it.fix, why = it.why)
        }
    }

    /**
     * One turn of the free conversation. [history] holds (speaker, text) pairs,
     * speaker "tutor" or "learner", oldest first; the last one is what the learner just said
     * (or nothing, to start).
     */
    suspend fun chat(history: List<Pair<String, String>>, level: String, topic: WordTopic?): ChatReply {
        val text = call(
            mapOf(
                "task" to "chat",
                "level" to level,
                "topic" to (topic?.en ?: ""),
                "history" to history.takeLast(16).map { (who, said) -> mapOf("who" to who, "text" to said.take(400)) },
            ),
            timeoutMs = 45_000,
        )
        return json.decodeFromString<ChatReply>(text)
    }

    private suspend fun signIn(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return it.uid }
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: error("Anonymous sign-in returned no user")
    }

    private suspend fun call(payload: Map<String, Any>, timeoutMs: Long = 90_000): String {
        check(configured) { "Firebase is not configured" }
        signIn()
        val result = withTimeout(timeoutMs) {
            FirebaseFunctions.getInstance(REGION)
                .getHttpsCallable("tutor")
                .call(payload)
                .await()
        }
        val data = result.getData() as? Map<*, *> ?: error("Unexpected response")
        return data["json"] as? String ?: error("Response has no json field")
    }

    /** The tutor's answer in conversation mode. */
    @Serializable
    data class ChatReply(
        /** The learner's sentence said correctly, empty when it was fine. */
        val fixed: String = "",
        /** Short Turkish note on the correction. */
        val note: String = "",
        /** The tutor's next line in English. */
        val reply: String = "",
    )

    @Serializable
    private data class WordBatch(val words: List<Word>)

    @Serializable
    private data class GrammarBatch(val items: List<RawItem>)

    @Serializable
    private data class RawItem(val s: String, val ok: Boolean, val fix: String = "", val why: String = "")

    companion object {
        const val REGION = "europe-west1"
    }
}
