/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private const val APPLE_METADATA_KEY_MEDIA_ID =
        "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"

    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 100
    }

    fun putAndGet(metadata: MediaMetadata): Metadata? {
        val mediaId = firstNotBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.getString(APPLE_METADATA_KEY_MEDIA_ID)
        )

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        return put(mediaId, title, artist, duration)
    }

    fun putPlaybackItem(playbackItem: Any?): Metadata? {
        playbackItem ?: return null
        val mediaId = callString(playbackItem, "getId")
        val title = firstNotBlank(
            callString(playbackItem, "getNowPlayingTitle"),
            callString(playbackItem, "getTitle")
        )
        val artist = firstNotBlank(
            callString(playbackItem, "getArtistName"),
            callString(playbackItem, "getNowPlayingSubtitle")
        )
        val duration = normalizeDuration(callLong(playbackItem, "getPlaybackDuration"))

        return put(mediaId, title, artist, duration)
    }

    fun put(
        mediaId: String?,
        title: String?,
        artist: String?,
        duration: Long
    ): Metadata? {
        if (mediaId.isNullOrBlank()) return null

        val newMetadata = Metadata(mediaId, title, artist, duration)
        metadataCache[mediaId] = newMetadata
        return newMetadata
    }

    fun getMetadataById(mediaId: String): Metadata? = metadataCache[mediaId]

    private fun callString(instance: Any, methodName: String): String? =
        runCatching { XposedHelpers.callMethod(instance, methodName) as? String }
            .getOrNull()

    private fun callLong(instance: Any, methodName: String): Long =
        runCatching {
            when (val value = XposedHelpers.callMethod(instance, methodName)) {
                is Number -> value.toLong()
                else -> 0L
            }
        }.getOrDefault(0L)

    private fun firstNotBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun normalizeDuration(duration: Long): Long {
        if (duration <= 0L) return 0L
        return if (duration < 24L * 60L * 60L) duration * 1000L else duration
    }

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long
    )
}
