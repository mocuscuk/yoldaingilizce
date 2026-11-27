package com.mocuscuk.yoldaingilizce.lesson

import android.os.SystemClock
import android.util.Log
import com.mocuscuk.yoldaingilizce.ai.AiClient
import com.mocuscuk.yoldaingilizce.audio.Ear
import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.audio.Speaker
import com.mocuscuk.yoldaingilizce.audio.VoiceCommands
import com.mocuscuk.yoldaingilizce.data.ContentRepository
import com.mocuscuk.yoldaingilizce.data.ProgressStore
import com.mocuscuk.yoldaingilizce.data.TripTracker
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

/**
 * What the driver can "say": with the steering wheel buttons (NEXT, PREV)
 * or by voice ("biliyorum", "bilmiyorum", "tekrar").
 */
enum class Input { NEXT, PREV, REPEAT }

/** Thrown when the driver says "bitir": the lesson stops and the trip summary is spoken. */
class EndTrip : Exception("Trip ended by voice")

/** What came back while waiting for the driver to talk. */
sealed interface Heard {
    data class Speech(val text: String) : Heard
    data class Button(val input: Input) : Heard
    data object Silence : Heard
}

/**
 * Everything a lesson needs: speech, the car buttons, pause/resume and storage.
 * Lessons are plain suspend functions that run on the main thread.
 */
class LessonContext(
    val speaker: Speaker,
    val store: ProgressStore,
    val content: ContentRepository,
    val ai: AiClient,
    val trip: TripTracker,
    /** Speech recognition, or null when voice answers are switched off. */
    private val ear: Ear?,
    /** For background work that must not hold up the lesson. */
    val scope: CoroutineScope,
    private val onDisplay: (title: String, subtitle: String) -> Unit,
) {
    private val paused = MutableStateFlow(false)
    private val input = MutableStateFlow<Input?>(null)
    private val ended = MutableStateFlow(false)
    private object PauseEvent
    private object EndEvent

    /** True after "bitir" (voice or button). */
    val endRequested: Boolean get() = ended.value

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

    /** True when voice answers can be used right now. */
    val voiceOn: Boolean get() = ear != null && store.value.voice && ear.available

    private fun rate(factor: Float): Float = (if (store.value.slow) 0.75f else 1.0f) * factor

    /**
     * Speaks text. If paused in the middle, it says the sentence again on resume.
     * When [interruptible], a button press cuts it short (the press stays queued).
     */
    suspend fun say(text: String, lang: Lang = Lang.EN, factor: Float = 1f, interruptible: Boolean = false) {
        while (true) {
            if (ended.value) throw EndTrip()
            paused.first { !it }
            if (ended.value) throw EndTrip()
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
            if (ended.value) throw EndTrip()
            if (done) return
            if (interruptible && input.value != null) return
            if (!paused.value) return // TTS error: do not loop forever
        }
    }

    suspend fun tr(text: String, interruptible: Boolean = false) = say(text, Lang.TR, 1f, interruptible)

    /** Short silence that also respects pause. */
    suspend fun gap(ms: Long = 400) {
        if (ended.value) throw EndTrip()
        paused.first { !it }
        delay(ms)
        if (ended.value) throw EndTrip()
    }

    /**
     * Waits for an answer: a button press or, when voice is on, a spoken command.
     * Returns null when nothing came in time.
     * Time spent paused (e.g. while the navigation speaks) does not count.
     * Saying "bitir" throws [EndTrip].
     */
    suspend fun awaitInput(timeoutMs: Long): Input? {
        var remaining = timeoutMs
        while (true) {
            paused.first { !it }
            val start = SystemClock.elapsedRealtime()
            val event: Any? = withTimeoutOrNull(remaining) {
                coroutineScope {
                    val listener = if (voiceOn) launch { listenForCommand() } else null
                    val e = merge(input.filterNotNull(), paused.filter { it }.map { PauseEvent }, ended.filter { it }.map { EndEvent }).first()
                    listener?.cancel()
                    e
                }
            }
            when (event) {
                null -> return null
                is Input -> {
                    input.value = null
                    return event
                }
                EndEvent -> throw EndTrip()
                else -> {
                    val used = SystemClock.elapsedRealtime() - start
                    remaining = maxOf(remaining - used, 3_000)
                }
            }
        }
    }

    /**
     * Like [awaitInput], but "tekrar" runs [again] (e.g. says the question once more)
     * and keeps waiting. Buttons never mean "repeat", so they behave as before.
     */
    suspend fun awaitAnswer(timeoutMs: Long, again: suspend () -> Unit): Input? {
        repeat(3) {
            val answer = awaitInput(timeoutMs)
            if (answer != Input.REPEAT) return answer
            clearInput()
            again()
        }
        return awaitInput(timeoutMs).takeIf { it != Input.REPEAT }
    }

    /**
     * Listens for a free sentence (conversation mode). A button press also ends the wait.
     * Pausing (navigation talking) restarts the listening afterwards.
     */
    suspend fun listenText(lang: Lang, timeoutMs: Long): Heard {
        val e = ear ?: return Heard.Silence
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            paused.first { !it }
            if (ended.value) throw EndTrip()
            val result: Any? = coroutineScope {
                val speech = async { e.hear(lang, longSpeech = true) }
                val other = async {
                    merge(input.filterNotNull(), paused.filter { it }.map { PauseEvent }, ended.filter { it }.map { EndEvent }).first()
                }
                val r = kotlinx.coroutines.selects.select<Any?> {
                    speech.onAwait { it }
                    other.onAwait { it }
                }
                speech.cancel()
                other.cancel()
                r
            }
            when (result) {
                null -> return Heard.Silence // recognition not possible
                is Input -> {
                    input.value = null
                    return Heard.Button(result)
                }
                EndEvent -> throw EndTrip()
                PauseEvent -> continue
                is List<*> -> {
                    val text = result.firstOrNull() as? String
                    if (!text.isNullOrBlank()) return Heard.Speech(text)
                    delay(200)
                }
            }
        }
        return Heard.Silence
    }

    /** Lets the summary itself be spoken after [EndTrip]. */
    fun clearEnd() {
        ended.value = false
        input.value = null
    }

    /** Ends the lesson with the trip summary (the "Bitir" button). */
    fun endTrip() {
        ended.value = true
        speaker.stop()
    }

    private suspend fun listenForCommand() {
        val e = ear ?: return
        while (true) {
            val heard = e.hear(Lang.TR) ?: return
            when (VoiceCommands.parse(heard)) {
                VoiceCommands.Command.YES -> input.value = Input.NEXT
                VoiceCommands.Command.NO -> input.value = Input.PREV
                VoiceCommands.Command.REPEAT -> input.value = Input.REPEAT
                VoiceCommands.Command.END -> ended.value = true
                null -> {
                    delay(250)
                    continue
                }
            }
            return
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
