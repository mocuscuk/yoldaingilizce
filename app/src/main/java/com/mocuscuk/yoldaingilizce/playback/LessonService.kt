package com.mocuscuk.yoldaingilizce.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mocuscuk.yoldaingilizce.App
import com.mocuscuk.yoldaingilizce.R
import com.mocuscuk.yoldaingilizce.audio.Ear
import com.mocuscuk.yoldaingilizce.audio.Speaker
import com.mocuscuk.yoldaingilizce.lesson.Input
import com.mocuscuk.yoldaingilizce.lesson.LessonContext
import com.mocuscuk.yoldaingilizce.lesson.Lessons
import com.mocuscuk.yoldaingilizce.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The media service Android Auto and Bluetooth talk to. It owns the lesson
 * that is running, the speech engine and audio focus.
 */
@OptIn(UnstableApi::class)
class LessonService : MediaLibraryService(), LessonPlayer.Listener {

    private val scope = MainScope()
    private lateinit var speaker: Speaker
    private lateinit var ear: Ear
    private val main = Handler(Looper.getMainLooper())
    private lateinit var player: LessonPlayer
    private var session: MediaLibrarySession? = null

    private var lessonJob: Job? = null
    private var lesson: LessonContext? = null

    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var pausedByFocus = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        ear = Ear(this)
        player = LessonPlayer(Looper.getMainLooper(), this)
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // When the navigation talks we pause instead of being ducked.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yolda:lesson")
            .apply { setReferenceCounted(false) }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val speedButton = CommandButton.Builder()
            .setDisplayName("Hız")
            .setIconResId(R.drawable.ic_speed)
            .setSessionCommand(SessionCommand(CMD_SPEED, Bundle.EMPTY))
            .build()
        val endButton = CommandButton.Builder()
            .setDisplayName("Bitir")
            .setIconResId(R.drawable.ic_stop)
            .setSessionCommand(SessionCommand(CMD_END, Bundle.EMPTY))
            .build()
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setCustomLayout(ImmutableList.of(speedButton, endButton))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        // Media3 posts its notification on the main thread; ours runs right after it.
        main.post { keepMicrophone() }
    }

    override fun onDestroy() {
        App.instance.trip.save()
        stopLesson()
        scope.cancel()
        speaker.shutdown()
        session?.release()
        session = null
        player.release()
        wakeLock?.release()
        super.onDestroy()
    }

    // ---- LessonPlayer.Listener ----

    override fun onStartLesson(id: String, playing: Boolean) {
        stopLesson()
        val app = App.instance
        val ctx = LessonContext(
            speaker = speaker,
            store = app.progress,
            content = app.content,
            ai = app.ai,
            trip = app.trip,
            ear = ear,
            scope = scope,
            onDisplay = { title, subtitle -> player.display(title, subtitle) },
        )
        ctx.setPaused(!playing)
        lesson = ctx
        lessonJob = scope.launch {
            try {
                Lessons.run(id, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "lesson failed", e)
            } finally {
                if (lesson === ctx) {
                    lesson = null
                    player.lessonFinished()
                    releaseAudio()
                }
            }
        }
    }

    override fun onPlayWhenReady(play: Boolean) {
        if (play) {
            val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            pausedByFocus = !granted
            lesson?.setPaused(!granted)
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } else {
            lesson?.setPaused(true)
            releaseAudio()
        }
    }

    override fun onNext() {
        lesson?.press(Input.NEXT)
    }

    override fun onPrevious() {
        lesson?.press(Input.PREV)
    }

    override fun onStopLesson() {
        App.instance.trip.save()
        stopLesson()
        releaseAudio()
    }

    // ---- helpers ----

    private fun stopLesson() {
        lesson = null
        lessonJob?.cancel()
        lessonJob = null
        speaker.stop()
    }

    private fun releaseAudio() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        pausedByFocus = false
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** The "Bitir" button: speak the trip summary and end. */
    private fun endTrip() {
        val current = lesson
        if (current != null) {
            current.endTrip()
            if (!player.playWhenReady) player.play()
        } else {
            player.setMediaItem(Catalog.item(Lessons.SUMMARY))
            player.prepare()
            player.play()
        }
    }

    /**
     * Android only lets a background app use the microphone from a foreground
     * service of type "microphone". Media3 starts ours as media playback only,
     * so we add the microphone type when voice answers are on. If Android refuses
     * (e.g. the trip was started from the car with the phone locked), the buttons still work.
     */
    private fun keepMicrophone() {
        if (Build.VERSION.SDK_INT < 30) return
        if (!player.playWhenReady || !ear.hasPermission || !App.instance.progress.value.voice) return
        val id = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        val notification = getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == id }?.notification ?: return
        try {
            ServiceCompat.startForeground(
                this, id, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "microphone not allowed in background", e)
        }
    }

    private fun onFocusChange(change: Int) {
        // Speech recognition borrows audio focus for a moment: that is not the navigation talking.
        if (ear.listening && change != AudioManager.AUDIOFOCUS_GAIN && change != AudioManager.AUDIOFOCUS_LOSS) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausedByFocus = true
                lesson?.setPaused(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus && player.playWhenReady) lesson?.setPaused(false)
                pausedByFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> player.pause()
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SPEED, Bundle.EMPTY))
                .add(SessionCommand(CMD_END, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SPEED) {
                App.instance.progress.update { it.copy(slow = !it.slow) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_END) {
                endTrip()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Catalog.root, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = Catalog.childrenOf(parentId)
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = Catalog.find(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { requested ->
                Catalog.find(requested.mediaId)?.takeIf { it.mediaMetadata.isPlayable == true }
                    ?: Catalog.item(Lessons.fromSearch(requested.requestMetadata.searchQuery))
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(Catalog.item(Lessons.JOURNEY)), 0, 0L)
            )
    }

    companion object {
        private const val TAG = "LessonService"
        const val CMD_SPEED = "yolda.SPEED"
        const val CMD_END = "yolda.END"
    }
}
