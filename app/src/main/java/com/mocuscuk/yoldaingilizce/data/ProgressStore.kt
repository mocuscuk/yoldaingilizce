package com.mocuscuk.yoldaingilizce.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** The learner's progress, kept in one small JSON file. */
class ProgressStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "progress.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Progress> = _state.asStateFlow()
    val value: Progress get() = _state.value

    @Synchronized
    fun update(change: (Progress) -> Progress) {
        _state.update(change)
        save(_state.value)
    }

    fun addXp(points: Int) = update { it.copy(xp = it.xp + points) }

    /** Counts today towards the daily streak. */
    fun markActiveToday() = update {
        val today = today()
        when (it.lastActiveDay) {
            today -> it
            today - 1 -> it.copy(streakDays = it.streakDays + 1, lastActiveDay = today)
            else -> it.copy(streakDays = 1, lastActiveDay = today)
        }
    }

    fun reset() = update { Progress(slow = it.slow) }

    private fun load(): Progress =
        runCatching { if (file.exists()) json.decodeFromString<Progress>(file.readText()) else null }
            .getOrNull() ?: Progress()

    private fun save(p: Progress) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(p))
            tmp.renameTo(file)
        }
    }

    companion object {
        fun today(): Long = LocalDate.now().toEpochDay()
    }
}
