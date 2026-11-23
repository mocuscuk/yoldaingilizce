package com.mocuscuk.yoldaingilizce.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.mocuscuk.yoldaingilizce.lesson.Lessons

/** The browse tree Android Auto shows: one root folder with the lessons. */
object Catalog {
    const val ROOT = "root"

    private val entries = listOf(
        Triple(Lessons.JOURNEY, "Yolculuk modu", "Kelime ve gramer, sırayla"),
        Triple(Lessons.VOCAB, "Kelime turu", "En yaygın kelimeler, aralıklı tekrar"),
        Triple(Lessons.GRAMMAR, "Gramer merdiveni", "Kolaydan zora"),
        Triple(Lessons.PLACEMENT, "Seviye tespiti", "Yaklaşık 10 dakika"),
    )

    val root: MediaItem = MediaItem.Builder()
        .setMediaId(ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Yolda İngilizce")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    val children: List<MediaItem> = entries.map { (id, title, subtitle) -> playable(id, title, subtitle) }

    fun find(id: String): MediaItem? = if (id == ROOT) root else children.firstOrNull { it.mediaId == id }

    fun item(id: String): MediaItem = find(id) ?: children.first()

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
