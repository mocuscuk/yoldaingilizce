package com.mocuscuk.yoldaingilizce.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mocuscuk.yoldaingilizce.lesson.Lessons

/**
 * A "player" that plays lessons instead of audio files. To the car it looks like
 * a music player, so the steering wheel buttons arrive here:
 * Next and Previous become answers, Play/Pause pauses the lesson.
 *
 * The playlist holds one item and repeats, so Next and Previous are always enabled.
 */
@OptIn(UnstableApi::class)
class LessonPlayer(
    looper: Looper,
    private val listener: Listener,
) : SimpleBasePlayer(looper) {

    interface Listener {
        fun onStartLesson(id: String, playing: Boolean)
        fun onPlayWhenReady(play: Boolean)
        fun onNext()
        fun onPrevious()
        fun onStopLesson()
    }

    private var playing = false
    private var active = false
    private var lessonId = Lessons.JOURNEY
    private var title = "Yolda İngilizce"
    private var subtitle = "Başlamak için oynat"

    /** Shown on the car screen and in the notification. Call on the main thread. */
    fun display(title: String, subtitle: String) {
        this.title = title
        this.subtitle = subtitle
        invalidateState()
    }

    fun lessonFinished() {
        active = false
        playing = false
        title = "Yolda İngilizce"
        subtitle = "Ders bitti. Tekrar başlamak için oynat"
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
            )
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setAlbumTitle("Yolda İngilizce")
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val item = MediaItem.Builder().setMediaId(lessonId).setMediaMetadata(metadata).build()
        val itemData = MediaItemData.Builder(/* uid= */ "lesson")
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0)
            .setRepeatMode(REPEAT_MODE_ALL)
            .setPlaybackState(if (active) STATE_READY else STATE_IDLE)
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val id = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
            ?: mediaItems.firstOrNull()?.mediaId
            ?: Lessons.JOURNEY
        start(id)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (!active) start(lessonId)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        if (playWhenReady && !active) start(lessonId)
        listener.onPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> listener.onNext()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> listener.onPrevious()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        active = false
        playing = false
        listener.onStopLesson()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (active) listener.onStopLesson()
        active = false
        return Futures.immediateVoidFuture()
    }

    private fun start(id: String) {
        lessonId = id
        active = true
        listener.onStartLesson(id, playing)
    }
}
