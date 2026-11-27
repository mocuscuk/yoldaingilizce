package com.mocuscuk.yoldaingilizce.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Speech recognition, one utterance at a time. Used only while the app is
 * silent (never while it speaks), so it does not hear itself.
 */
class Ear(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** True while the microphone is open. Audio focus changes are ignored then. */
    @Volatile var listening = false
        private set

    /** Set when recognition can not work at all on this phone (no permission, no engine). */
    @Volatile var broken = false
        private set

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val available: Boolean
        get() = !broken && hasPermission && SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens until the speaker stops talking. Returns the candidate transcripts
     * (empty when nothing was understood), or null when recognition is not possible.
     * Call on the main thread. Cancelling closes the microphone.
     */
    suspend fun hear(lang: Lang, longSpeech: Boolean = false): List<String>? {
        if (!available) return null
        return suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            var done = false
            fun finish(result: List<String>?) {
                if (done) return
                done = true
                listening = false
                main.post { runCatching { recognizer.destroy() } }
                if (cont.isActive) cont.resume(result)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            broken = true
                            finish(null)
                        }
                        else -> finish(emptyList())
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (longSpeech) {
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
                intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            }
            cont.invokeOnCancellation {
                done = true
                listening = false
                main.post {
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }
            try {
                listening = true
                recognizer.startListening(intent)
            } catch (e: Exception) {
                broken = true
                finish(null)
            }
        }
    }
}

/** Turkish voice answers mapped to the steering wheel meanings. */
object VoiceCommands {
    enum class Command { YES, NO, REPEAT, END }

    private val tr = java.util.Locale.forLanguageTag("tr-TR")

    private val end = listOf("bitir", "yolculuk bitti", "bitti", "kapat", "finish", "stop")
    private val repeat = listOf("tekrar", "bir daha", "yeniden", "anlamadım", "repeat", "again")
    private val no = listOf("bilmiyorum", "bilmiyom", "bilemedim", "bilmedim", "hayır", "yanlış", "değil", "no", "wrong", "don't know")
    private val yes = listOf("biliyorum", "biliyom", "bildim", "evet", "doğru", "yes", "correct", "i know")

    /** The first transcript that means something, or null. */
    fun parse(candidates: List<String>): Command? {
        for (raw in candidates) {
            val t = " " + raw.lowercase(tr).replace(Regex("[^\\p{L}' ]"), " ") + " "
            // "No" words first, so that "doğru değil" counts as no.
            when {
                end.any { " $it " in t } -> return Command.END
                repeat.any { it in t } -> return Command.REPEAT
                no.any { " $it " in t || (it.length > 5 && it in t) } -> return Command.NO
                yes.any { " $it " in t || (it.length > 5 && it in t) } -> return Command.YES
            }
        }
        return null
    }

    /** "Stop" words for the English conversation mode. */
    fun isEndOfChat(text: String): Boolean {
        val t = " " + text.lowercase(tr).replace(Regex("[^\\p{L}' ]"), " ") + " "
        return listOf("bitir", "stop", "finish", "goodbye", "bye bye", "end the conversation", "that's all")
            .any { " $it " in t }
    }
}
