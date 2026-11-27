package com.mocuscuk.yoldaingilizce.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mocuscuk.yoldaingilizce.data.WORD_TOPICS
import com.mocuscuk.yoldaingilizce.lesson.Lessons

/** The browse tree Android Auto shows: the lessons, plus a folder of word themes. */
object Catalog {
    const val ROOT = "root"
    const val TOPICS = "topics"

    private val entries = listOf(
        Triple(Lessons.JOURNEY, "Yolculuk modu", "Kelime ve gramer, sırayla"),
        Triple(Lessons.CHAT, "Serbest sohbet", "Yapay zekayla İngilizce konuş"),
        Triple(Lessons.VOCAB, "Kelime turu", "En yaygın kelimeler, aralıklı tekrar"),
        Triple(Lessons.GRAMMAR, "Gramer merdiveni", "Kolaydan zora"),
        Triple(Lessons.PLACEMENT, "Seviye tespiti", "Yaklaşık 10 dakika"),
        Triple(Lessons.SUMMARY, "Yolculuk özeti", "Bugün ne öğrendin, serin kaç gün"),
    )

    val root: MediaItem = folder(ROOT, "Yolda İngilizce", "")
    val topicsFolder: MediaItem = folder(TOPICS, "Kelime konusu", "İş, seyahat, günlük konuşma")

    /** Playable lessons, for the phone screen. */
    val lessons: List<MediaItem> = entries.map { (id, title, subtitle) -> playable(id, title, subtitle) }

    /** Picking one sets the word theme and starts the journey. */
    val topics: List<MediaItem> =
        WORD_TOPICS.map { playable(Lessons.TOPIC_PREFIX + it.id, it.tr, "Yolculuk modu, bu konudan kelimeler") } +
            playable(Lessons.TOPIC_PREFIX + Lessons.TOPIC_ALL, "Karışık", "Konu seçimini kaldır")

    /** What the car shows at the top level. */
    val children: List<MediaItem> = lessons + topicsFolder

    fun childrenOf(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> children
        TOPICS -> topics
        else -> emptyList()
    }

    fun find(id: String): MediaItem? = when (id) {
        ROOT -> root
        TOPICS -> topicsFolder
        else -> (lessons + topics).firstOrNull { it.mediaId == id }
    }

    fun item(id: String): MediaItem = find(id)?.takeIf { it.mediaMetadata.isPlayable == true } ?: lessons.first()

    private fun folder(id: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun playable(id: String, title: String, subtitle: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build()
            )
            .build()
}
