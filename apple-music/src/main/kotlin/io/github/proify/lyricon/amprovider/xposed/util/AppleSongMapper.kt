/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.util

import android.text.Html
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

fun AppleSong.toSong(): Song = AppleSongMapper.map(this)

object AppleSongMapper {
    private const val TAG = "Lyricon_AppleBridge"
    private val BACKING_VOCAL_TOKEN = Regex(
        "^(?:o+h+|a+h+|h+a+|woo+|whoa+|yeah+|hey+|la+|na+)[,.!?~…]*$",
        RegexOption.IGNORE_CASE
    )

    fun map(song: AppleSong): Song {
        return Song(
            id = song.adamId,
            name = song.name,
            artist = song.artist,
            duration = song.duration.toLong(),
            lyrics = convertLyrics(song.lyrics)
        )
    }

    private fun convertLyrics(appleLyrics: List<LyricLine>): MutableList<RichLyricLine> {
        val keepFlags = appleLyrics.map { shouldKeepLeadLyricLine(it) }

        return appleLyrics.filterIndexed { index, _ -> keepFlags.getOrElse(index) { false } }
            .map { appleLine ->
            RichLyricLine().apply {

                begin = appleLine.begin.toLong()
                end = appleLine.end.toLong()
                duration = appleLine.duration.toLong()

                text = appleLine.htmlLineText
                words = appleLine.words.map { it.toLyricWord() }.toMutableList()

                translation = appleLine.htmlTranslationLineText
//                translationWords = listOf(
//                    LyricWord(
//                        begin = begin,
//                        end = end,
//                        duration = duration,
//                        text = appleLine.htmlTranslationLineText
//                    )
//                )
            }
        }.toMutableList()
    }

    private fun shouldKeepLeadLyricLine(line: LyricLine): Boolean {
        val lead = cleanPlainText(line.htmlLineText)
        if (lead.isBlank()) return false

        val background = cleanPlainText(line.htmlBackgroundVocalsLineText)
        val hasLeadWords = line.words.any { cleanPlainText(it.text.orEmpty()).isNotBlank() }
        val hasBackgroundWords =
            line.backgroundWords.any { cleanPlainText(it.text.orEmpty()).isNotBlank() }

        if (!hasLeadWords && (hasBackgroundWords || background.isNotBlank())) {
            return false
        }
        if (sameNormalizedLyric(lead, background)) {
            return false
        }
        if (hasBackgroundWords && isShortBackingVocal(lead)) {
            return false
        }
        return true
    }

    private fun isShortBackingVocal(text: String): Boolean {
        val normalized = cleanPlainText(text)
            .replace(" ", "")
            .trim()
        return normalized.length <= 8 && BACKING_VOCAL_TOKEN.matches(normalized)
    }

    private fun sameNormalizedLyric(left: String, right: String): Boolean {
        val normalizedLeft = normalizeForCompare(left)
        val normalizedRight = normalizeForCompare(right)
        return normalizedLeft.isNotBlank() && normalizedLeft == normalizedRight
    }

    private fun normalizeForCompare(value: String): String {
        return cleanPlainText(value)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
    }

    private fun cleanPlainText(text: String?): String {
        val raw = text.orEmpty()
        val stripped = if (raw.indexOf('<') >= 0 || raw.indexOf('&') >= 0) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            raw
        }
        return stripped
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun io.github.proify.lyricon.amprovider.xposed.model.LyricWord.toLyricWord(): LyricWord =
        LyricWord(
            text = this.text,
            begin = this.begin.toLong(),
            duration = this.duration.toLong(),
            end = this.end.toLong()
        )
}
