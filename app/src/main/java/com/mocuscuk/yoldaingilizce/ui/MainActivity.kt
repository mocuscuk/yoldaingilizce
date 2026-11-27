package com.mocuscuk.yoldaingilizce.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mocuscuk.yoldaingilizce.App
import com.mocuscuk.yoldaingilizce.data.Progress
import com.mocuscuk.yoldaingilizce.data.WORD_TOPICS
import com.mocuscuk.yoldaingilizce.lesson.tripLines
import com.mocuscuk.yoldaingilizce.lesson.Lessons
import com.mocuscuk.yoldaingilizce.playback.Catalog
import com.mocuscuk.yoldaingilizce.playback.LessonService

/**
 * The phone screen: an evening summary plus big buttons for testing at home.
 * In the car everything works through Android Auto and the steering wheel.
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var nowTitle by mutableStateOf("")
    private var nowSubtitle by mutableStateOf("")
    private var isPlaying by mutableStateOf(false)

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            nowTitle = mediaMetadata.title?.toString().orEmpty()
            nowSubtitle = mediaMetadata.artist?.toString().orEmpty()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // For voice answers and the conversation mode.
            if (App.instance.progress.value.voice) add(Manifest.permission.RECORD_AUDIO)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, LessonService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(playerListener)
            nowTitle = c.mediaMetadata.title?.toString().orEmpty()
            nowSubtitle = c.mediaMetadata.artist?.toString().orEmpty()
            isPlaying = c.isPlaying
            controller = c
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun startLesson(id: String) {
        val c = controller ?: return
        c.setMediaItem(Catalog.item(id))
        c.prepare()
        c.play()
    }

    private fun endTrip() {
        controller?.sendCustomCommand(SessionCommand(LessonService.CMD_END, Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun micAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun HomeScreen() {
        val app = App.instance
        val progress by app.progress.state.collectAsStateWithLifecycle()
        var uid by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { uid = app.ai.uid() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Yolda İngilizce", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            StatsCard(progress)

            if (nowTitle.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Şimdi", style = MaterialTheme.typography.labelMedium)
                        Text(nowTitle, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(nowSubtitle, fontSize = 18.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { controller?.seekToPrevious() }, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text("Geri\nbilmiyorum")
                }
                Button(
                    onClick = { controller?.let { if (it.isPlaying) it.pause() else if (it.playbackState == Player.STATE_IDLE) startLesson(Lessons.JOURNEY) else it.play() } },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Text(if (isPlaying) "Duraklat" else "Oynat")
                }
                OutlinedButton(onClick = { controller?.seekToNext() }, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text("İleri\nbiliyorum")
                }
            }

            OutlinedButton(onClick = { endTrip() }, modifier = Modifier.fillMaxWidth()) {
                Text("Yolculuğu bitir ve özeti dinle")
            }

            Text("Başlat", style = MaterialTheme.typography.titleMedium)
            Catalog.lessons.forEach { item ->
                Button(onClick = { startLesson(item.mediaId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(item.mediaMetadata.title?.toString().orEmpty())
                }
            }

            Text("Kelime konusu", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = progress.wordTopic.isEmpty(),
                    onClick = { app.progress.update { it.copy(wordTopic = "") } },
                    label = { Text("Karışık") },
                )
                WORD_TOPICS.forEach { topic ->
                    FilterChip(
                        selected = progress.wordTopic == topic.id,
                        onClick = { app.progress.update { it.copy(wordTopic = topic.id) } },
                        label = { Text(topic.tr.removeSuffix(" İngilizcesi").removeSuffix(" konuşma")) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Yavaş konuş", modifier = Modifier.weight(1f))
                Switch(checked = progress.slow, onCheckedChange = { slow -> app.progress.update { it.copy(slow = slow) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sesli cevap")
                    Text(
                        if (micAllowed()) "Biliyorum, bilmiyorum, tekrar, bitir"
                        else "Mikrofon izni yok. Açınca izin isteyeceğim.",
                        fontSize = 13.sp,
                    )
                }
                Switch(checked = progress.voice, onCheckedChange = { on ->
                    app.progress.update { it.copy(voice = on) }
                    if (on && !micAllowed()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                })
            }

            Spacer(Modifier.height(8.dp))
            Text("Yapay zeka", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !app.ai.configured -> "Kapalı: bu sürüm google-services.json olmadan derlendi. Dersler yerleşik içerikle çalışır."
                    uid == null -> "Firebase'e bağlanılıyor…"
                    else -> "Cihaz kimliği (ALLOWED_UIDS için):\n$uid"
                },
                fontSize = 14.sp,
            )
        }
    }

    @Composable
    private fun StatsCard(p: Progress) {
        val topic = App.instance.content.topics.getOrNull(p.grammarTopic)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (p.placementDone) "Seviye ${p.level}" else "Seviye tespiti bekliyor",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("${p.xp} XP  ·  ${p.streakDays} günlük seri")
                Text("Öğrenilen kelime: ${p.learnedWords}  (görülen ${p.seenWords})")
                Text("Gramer basamağı: ${p.grammarTopic + 1}. ${topic?.tr ?: ""}")
                if (p.grammarAnswered > 0) {
                    Text("Gramer doğruluk: %${p.grammarCorrect * 100 / p.grammarAnswered}")
                }
                p.lastTrip?.takeIf { !it.empty }?.let { trip ->
                    Spacer(Modifier.height(4.dp))
                    Text("Son yolculuk (${trip.minutes} dk)", fontWeight = FontWeight.SemiBold)
                    tripLines(trip).forEach { Text(it.replace("Bu yolculukta ", ""), fontSize = 14.sp) }
                }
            }
        }
    }
}
