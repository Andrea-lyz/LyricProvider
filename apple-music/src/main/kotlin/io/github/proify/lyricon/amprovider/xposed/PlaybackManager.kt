/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.util.Log
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import kotlin.system.measureTimeMillis

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null
    private var application: Application? = null

    // 状态追踪
    private var currentSongId: String? = null
    private var currentPlaybackItem: Any? = null
    private var lastLyricRequestKey: String? = null

    fun init(remotePlayer: RemotePlayer, requester: LyricRequester, application: Application) {
        this.player = remotePlayer
        this.lyricRequester = requester
        this.application = application
    }

    /**
     * 当系统切歌或 Metadata 变化时调用
     */
    fun onSongChanged(newId: String?) {
        onSongChanged(newId, requestIfMissing = true)
    }

    fun onPlaybackItemObserved(
        playbackItem: Any?,
        source: String,
        requestIfMissing: Boolean
    ) {
        val metadata = MediaMetadataCache.putPlaybackItem(playbackItem)
        if (metadata == null) {
            YLog.debug("PlaybackManager: Ignored playback item without id from $source")
            return
        }

        currentPlaybackItem = playbackItem
        YLog.debug(
            "PlaybackManager: Playback item from $source, id=${metadata.id}, " +
                "title=${metadata.title.orEmpty()}"
        )
        onSongChanged(metadata.id, requestIfMissing)
    }

    private fun onSongChanged(newId: String?, requestIfMissing: Boolean) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            currentPlaybackItem = null
            lastLyricRequestKey = null
            setSong(null)
            YLog.debug("PlaybackManager: Song changed to null")
            return
        }

        // 避免重复处理同一首歌
        if (newId == currentSongId) {
            if (requestIfMissing && lastSong?.lyrics.isNullOrEmpty()) {
                requestLyrics(newId)
            }
            return
        }
        currentSongId = newId

        YLog.debug("PlaybackManager: Song changed to $newId")

        // 1. 立即设置歌曲（可能是完整版，也可能是占位版）
        val song = SongRepository.getSong(newId)
        setSong(song)

        // 2. 检查是否需要下载歌词
        if (song.lyrics.isNullOrEmpty()) {
            if (requestIfMissing) {
                requestLyrics(newId)
            }
        } else {
            YLog.debug("PlaybackManager: Song $newId has lyrics, skipping download.")
        }
    }

    /**
     * 当 Hook 捕获到歌词构建完成时调用
     */
    fun onLyricsBuilt(nativeSongObj: Any) {
        val song = SongRepository.saveSong(nativeSongObj)
        if (song == null) {
            YLog.debug("PlaybackManager: Failed to save song.")
            return
        }
        val id = song.id
        if (currentSongId == null) {
            currentSongId = id
            YLog.debug("PlaybackManager: Adopted lyrics song id as current song $id")
        }

        var changed = false
        val time = measureTimeMillis {
            changed = lastSong != song
        }
        Log.d("PlaybackManager", "Same song check took $time ms.")

        if (id == currentSongId && changed) {
            YLog.debug("PlaybackManager: Lyrics ready for current song $id, updating player.")
            setSong(song)
        } else {
            YLog.debug(
                "PlaybackManager: Lyrics ready for song $id, " +
                    "current=$currentSongId, changed=$changed"
            )
        }
    }

    private var lastSong: Song? = null
    private fun setSong(song: Song?) {
        lastSong = song
        player?.setSong(song)
        SaltLyricBridge.send(application, song)
    }

    private fun requestLyrics(mediaId: String) {
        val playbackItem = currentPlaybackItem
        val playbackItemMetadata = MediaMetadataCache.putPlaybackItem(playbackItem)
        val requestKey =
            if (playbackItem != null && playbackItemMetadata?.id == mediaId) {
                "$mediaId:playbackItem:${System.identityHashCode(playbackItem)}"
            } else {
                "$mediaId:mediaId"
            }
        if (requestKey == lastLyricRequestKey) {
            YLog.debug("PlaybackManager: Skip duplicate lyric request $requestKey")
            return
        }

        lastLyricRequestKey = requestKey
        if (playbackItem != null && playbackItemMetadata?.id == mediaId) {
            lyricRequester?.requestDownload(playbackItem)
            return
        }
        lyricRequester?.requestDownload(mediaId)
    }
}
