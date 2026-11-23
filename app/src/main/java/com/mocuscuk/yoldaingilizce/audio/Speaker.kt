package com.mocuscuk.yoldaingilizce.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Lang(val locale: Locale) {
    EN(Locale.US),
    TR(Locale.forLanguageTag("tr-TR")),
}

/** Text to speech that can be awaited, one sentence at a time. */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    /** Speaks the text. Returns true when it was spoken to the end, false when stopped. */
    suspend fun speak(text: String, lang: Lang, rate: Float): Boolean {
        if (!ready.await() || text.isBlank()) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        tts.language = lang.locale
        tts.setSpeechRate(rate)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return try {
            done.await()
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(false) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finish(id: String?, ok: Boolean) {
        if (id == null) return
        pending[id]?.complete(ok)
    }
}
