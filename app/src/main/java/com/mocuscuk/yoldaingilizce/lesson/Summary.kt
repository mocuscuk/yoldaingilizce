package com.mocuscuk.yoldaingilizce.lesson

import com.mocuscuk.yoldaingilizce.data.ProgressStore
import com.mocuscuk.yoldaingilizce.data.TripSummary

/** End of the drive: what you did on this trip and your daily streak, read aloud. */
suspend fun LessonContext.tripSummary() {
    val t = trip.finish(spoken = true)
    if (!t.empty) store.markActiveToday()
    val p = store.value
    show("Yolculuk özeti", if (t.empty) "Bu yolculukta ders yok" else "${t.words} kelime · ${p.streakDays} günlük seri")
    tr("Yolculuk özeti.")
    if (t.empty) {
        tr("Bu yolculukta henüz ders yapmadık.")
    } else {
        tripLines(t).forEach { tr(it) }
        if (p.learnedWords > 0) tr("Toplamda ${p.learnedWords} kelimeyi kalıcı olarak öğrendin.")
    }
    streakLine(p.streakDays, p.lastActiveDay == ProgressStore.today())?.let { tr(it) }
    tr("İyi yolculuklar!")
}

/** The spoken lines for one trip, also used for "last time" at the start of a drive. */
fun tripLines(t: TripSummary): List<String> = buildList {
    if (t.words > 0) {
        add("Bu yolculukta ${t.words} kelime çalıştın, ${t.known} tanesini bildin.")
        if (t.newWords > 0) add("${t.newWords} yeni kelimeyle tanıştın.")
    }
    if (t.grammar > 0) add("Gramerde ${t.grammar} sorudan ${t.grammarRight} tanesi doğru.")
    if (t.chatTurns > 0) add("Sohbette ${t.chatTurns} kez İngilizce cevap verdin.")
    if (t.xp > 0) add("${t.xp} puan kazandın.")
}

private fun streakLine(days: Int, activeToday: Boolean): String? = when {
    days <= 0 -> null
    !activeToday -> "Serin $days gün. Bugün biraz dinlersen seri devam eder."
    days == 1 -> "Bugün yeni bir seri başladı. Yarın da dinlersen iki gün olacak."
    else -> "Serin $days gün oldu, harika! Yarın da dinlersen ${days + 1} gün olacak."
}
