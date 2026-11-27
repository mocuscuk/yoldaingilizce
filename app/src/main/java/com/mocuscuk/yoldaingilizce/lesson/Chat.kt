package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.ai.AiClient
import com.mocuscuk.yoldaingilizce.audio.Lang
import com.mocuscuk.yoldaingilizce.audio.VoiceCommands
import com.mocuscuk.yoldaingilizce.data.wordTopic
import kotlinx.coroutines.CancellationException

/**
 * Free conversation with the AI, by voice only. The tutor talks, the learner
 * answers in English, and the tutor says the corrected sentence before replying.
 * Next = new subject, Previous or "repeat" = say the last line again,
 * "stop" / "bitir" = end. Returns false when it can not run here.
 */
suspend fun LessonContext.chat(): Boolean {
    show("Serbest sohbet", "İngilizce konuş")
    if (!ai.configured) {
        tr("Sohbet modu için yapay zekanın bağlı olması gerekiyor. Yolculuk moduna geçiyorum.")
        return false
    }
    if (!voiceOn) {
        tr("Sohbet modu için mikrofon izni gerekiyor. Araba dururken telefonda uygulamayı açıp sesli cevabı aç. Şimdilik yolculuk moduna geçiyorum.")
        return false
    }
    tr("Serbest sohbet. Ben İngilizce konuşacağım, sen de İngilizce cevap ver. Hata yaparsan doğrusunu söyleyeceğim.")
    tr("Bitirmek için stop de. İleri tuşu yeni konu açar, geri tuşu son cümlemi tekrarlar.")

    val history = mutableListOf<Pair<String, String>>()
    var reply = askTutor(history)?.reply ?: return true
    var silences = 0
    var turns = 0
    history.add("tutor" to reply)
    while (true) {
        show("Sohbet", reply)
        say(reply, Lang.EN, 0.95f)
        when (val heard = listenText(Lang.EN, 15_000)) {
            Heard.Silence -> {
                silences++
                if (silences >= 2) {
                    tr("Sessiz kaldın, sohbeti burada bırakıyorum.")
                    break
                }
                tr("Acele etme, bir daha söylüyorum.")
            }
            is Heard.Button -> when (heard.input) {
                Input.NEXT -> {
                    history.add("learner" to "Let's talk about something else, please.")
                    reply = askTutor(history)?.reply ?: break
                    history.add("tutor" to reply)
                }
                else -> Unit // say the same line again
            }
            is Heard.Speech -> {
                val text = heard.text
                if (VoiceCommands.isEndOfChat(text)) break
                if (VoiceCommands.parse(listOf(text)) == VoiceCommands.Command.REPEAT && text.split(" ").size <= 2) {
                    continue
                }
                silences = 0
                show("Sen", text)
                history.add("learner" to text)
                val answer = askTutor(history) ?: break
                turns++
                trip.chatTurn()
                store.addXp(8)
                if (answer.fixed.isNotBlank() && !sameSentence(answer.fixed, text)) {
                    show("Doğrusu", answer.fixed)
                    tr("Küçük bir düzeltme. Şöyle söyleyebilirsin:")
                    say(answer.fixed, Lang.EN, 0.9f)
                    if (answer.note.isNotBlank()) tr(answer.note)
                    gap(300)
                }
                reply = answer.reply.ifBlank { "Tell me more." }
                history.add("tutor" to reply)
            }
        }
    }
    store.markActiveToday()
    if (turns > 0) tr("Sohbet bitti. $turns kez cevap verdin, eline sağlık!")
    return true
}

/** One call to the tutor. Says what went wrong and returns null when there is no connection. */
private suspend fun LessonContext.askTutor(history: List<Pair<String, String>>): AiClient.ChatReply? {
    show("Sohbet", "Düşünüyor…")
    return try {
        ai.chat(history, store.value.level, wordTopic(store.value.wordTopic))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("Chat", "tutor call failed", e)
        tr("Şu an yapay zekaya ulaşamıyorum, sohbete ara veriyorum.")
        null
    }
}

private fun sameSentence(a: String, b: String): Boolean {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
    return norm(a) == norm(b)
}
