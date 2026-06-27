/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null
    private var application: Application? = null

    private var currentSongId: String? = null
    private var currentPlaybackItem: Any? = null
    private var lastLyricRequestKey: String? = null

    fun init(remotePlayer: RemotePlayer, requester: LyricRequester, application: Application) {
        this.player = remotePlayer
        this.lyricRequester = requester
        this.application = application
    }

    fun onSongChanged(newId: String?) {
        onSongChanged(newId, requestIfMissing = true)
    }

    fun onPlaybackItemObserved(
        playbackItem: Any?,
        requestIfMissing: Boolean
    ) {
        val metadata = MediaMetadataCache.putPlaybackItem(playbackItem) ?: return

        currentPlaybackItem = playbackItem
        onSongChanged(metadata.id, requestIfMissing)
    }

    private fun onSongChanged(newId: String?, requestIfMissing: Boolean) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            currentPlaybackItem = null
            lastLyricRequestKey = null
            setSong(null)
            return
        }

        if (newId == currentSongId) {
            if (requestIfMissing && lastSong?.lyrics.isNullOrEmpty()) {
                requestLyrics(newId)
            }
            return
        }
        currentSongId = newId

        val song = SongRepository.getSong(newId)
        setSong(song)

        if (song.lyrics.isNullOrEmpty() && requestIfMissing) {
            requestLyrics(newId)
        }
    }

    fun onLyricsBuilt(nativeSongObj: Any) {
        val song = SongRepository.saveSong(nativeSongObj) ?: return
        val id = song.id
        if (currentSongId == null) {
            currentSongId = id
        }

        if (id == currentSongId && lastSong != song) {
            setSong(song)
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
