package com.mocuscuk.yoldaingilizce.data

import android.os.SystemClock

/**
 * Counts what you do during one drive. A trip starts with the first answer and
 * ends with the spoken summary, or by itself after a long break.
 */
class TripTracker(private val store: ProgressStore) {
    private var startedAt = 0L
    private var lastActive = 0L
    private var xpAtStart = 0
    private var current = TripSummary()

    /** The trip so far (empty when nothing happened yet). */
    @Synchronized
    fun snapshot(): TripSummary {
        rollIfStale()
        if (current.empty) return current
        val minutes = ((lastActive - startedAt) / 60_000).toInt()
        return current.copy(
            day = ProgressStore.today(),
            minutes = minutes.coerceAtLeast(1),
            xp = (store.value.xp - xpAtStart).coerceAtLeast(0),
        )
    }

    @Synchronized
    fun word(isNew: Boolean, knew: Boolean?) = record {
        it.copy(
            words = it.words + 1,
            known = it.known + if (knew == true) 1 else 0,
            newWords = it.newWords + if (isNew) 1 else 0,
        )
    }

    @Synchronized
    fun grammar(correct: Boolean) = record {
        it.copy(grammar = it.grammar + 1, grammarRight = it.grammarRight + if (correct) 1 else 0)
    }

    @Synchronized
    fun chatTurn() = record { it.copy(chatTurns = it.chatTurns + 1) }

    /** Ends the trip after its summary was spoken. Returns that summary. */
    @Synchronized
    fun finish(spoken: Boolean): TripSummary {
        val trip = snapshot()
        if (!trip.empty) store.update { it.copy(lastTrip = trip.copy(spoken = spoken)) }
        current = TripSummary()
        startedAt = 0
        return trip
    }

    /** Saves the trip so far, e.g. when the lesson is stopped, without ending it. */
    @Synchronized
    fun save() {
        val trip = snapshot()
        if (!trip.empty) store.update { it.copy(lastTrip = trip.copy(spoken = false)) }
    }

    private inline fun record(change: (TripSummary) -> TripSummary) {
        rollIfStale()
        val now = SystemClock.elapsedRealtime()
        if (startedAt == 0L) {
            startedAt = now
            xpAtStart = store.value.xp
        }
        lastActive = now
        current = change(current)
    }

    /** After a long break the old trip is over: keep it for the phone and start fresh. */
    private fun rollIfStale() {
        if (startedAt == 0L) return
        if (SystemClock.elapsedRealtime() - lastActive < BREAK_MS) return
        val old = current
        if (!old.empty) {
            val minutes = ((lastActive - startedAt) / 60_000).toInt().coerceAtLeast(1)
            store.update { it.copy(lastTrip = old.copy(day = ProgressStore.today(), minutes = minutes, spoken = false)) }
        }
        current = TripSummary()
        startedAt = 0
    }

    companion object {
        private const val BREAK_MS = 40 * 60 * 1000L
    }
}
