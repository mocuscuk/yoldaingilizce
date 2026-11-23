package com.mocuscuk.yoldaingilizce.lesson

import android.os.SystemClock
import android.util.Log
import com.mocuscuk.yoldaingilizce.ai.AiClient
import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.audio.Speaker
import com.mocuscuk.yoldaingilizce.data.ContentRepository
import com.mocuscuk.yoldaingilizce.data.ProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the driver can "say" with the steering wheel buttons. */
enum class Input { NEXT, PREV }

/**
 * Everything a lesson needs: speech, the car buttons, pause/resume and storage.
 * Lessons are plain suspend functions that run on the main thread.
 */
class LessonContext(
    val speaker: Speaker,
    val store: ProgressStore,
    val content: ContentRepository,
    val ai: AiClient,
    /** For background work that must not hold up the lesson. */
    val scope: CoroutineScope,
    private val onDisplay: (title: String, subtitle: String) -> Unit,
) {
    private val paused = MutableStateFlow(false)
    private val input = MutableStateFlow<Input?>(null)
    private object PauseEvent

    fun press(i: Input) {
        input.value = i
    }

    fun setPaused(p: Boolean) {
        paused.value = p
        if (p) speaker.stop()
    }

    fun clearInput() {
        input.value = null
    }

    fun show(title: String, subtitle: String) = onDisplay(title, subtitle)

    private fun rate(factor: Float): Float = (if (store.value.slow) 0.75f else 1.0f) * factor

    /**
     * Speaks text. If paused in the middle, it says the sentence again on resume.
     * When [interruptible], a button press cuts it short (the press stays queued).
     */
    suspend fun say(text: String, lang: Lang = Lang.EN, factor: Float = 1f, interruptible: Boolean = false) {
        while (true) {
            paused.first { !it }
            if (interruptible && input.value != null) return
            val done = if (interruptible) {
                coroutineScope {
                    val speech = async { speaker.speak(text, lang, rate(factor)) }
                    val watcher = launch {
                        input.first { it != null }
                        speaker.stop()
                    }
                    val r = speech.await()
                    watcher.cancel()
                    r
                }
            } else {
                speaker.speak(text, lang, rate(factor))
            }
            if (done) return
            if (interruptible && input.value != null) return
            if (!paused.value) return // TTS error: do not loop forever
        }
    }

    suspend fun tr(text: String, interruptible: Boolean = false) = say(text, Lang.TR, 1f, interruptible)

    /** Short silence that also respects pause. */
    suspend fun gap(ms: Long = 400) {
        paused.first { !it }
        delay(ms)
    }

    /**
     * Waits for a button press. Returns null when nothing was pressed in time.
     * Time spent paused (e.g. while the navigation speaks) does not count.
     */
    suspend fun awaitInput(timeoutMs: Long): Input? {
        var remaining = timeoutMs
        while (true) {
            paused.first { !it }
            val start = SystemClock.elapsedRealtime()
            val event: Any? = withTimeoutOrNull(remaining) {
                merge(input.filterNotNull(), paused.filter { it }.map { PauseEvent }).first()
            }
            when (event) {
                null -> return null
                is Input -> {
                    input.value = null
                    return event
                }
                else -> {
                    val used = SystemClock.elapsedRealtime() - start
                    remaining = maxOf(remaining - used, 3_000)
                }
            }
        }
    }

    /** Runs a background job (e.g. AI prefetch) that must never break the lesson. */
    suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Lesson", "background step failed", e)
        }
    }
}
