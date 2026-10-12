package com.twig.app.ui

import com.twig.app.PlaylistTrack

/** Sorting keys for the playlist page. Album edition labels are display metadata, not a new
 * sequence of track numbers; use the base name and containing folder for album-track order. */
internal object MusicTrackSort {
    private val editionSuffix = Regex("\\s*\\([^)]*\\bedition\\b[^)]*\\)\\s*$", RegexOption.IGNORE_CASE)
    private val discFolder = Regex("^(?:cd|disc|disk)[\\s._-]*([1-9]\\d*)(?:[\\s._-].*)?$", RegexOption.IGNORE_CASE)

    /** CD/track tags may be "2", "02", or "2/3" (number / total). */
    fun tagNumber(value: String?): Int =
        value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    fun sorted(tracks: List<PlaylistTrack>, mode: Int): List<PlaylistTrack> =
        tracks.withIndex().sortedWith(compareBy<IndexedValue<PlaylistTrack>> {
            val t = it.value
            when (mode) {
                0 -> t.title.ifEmpty { t.name }.lowercase()
                1 -> t.album.ifEmpty { "\uffff" }.lowercase()
                2 -> t.artist.lowercase()
                3 -> "${t.artist.lowercase()}\u0000${albumTrackKey(t)}"
                4 -> t.path.lowercase()
                5 -> t.name.lowercase()
                7 -> albumTrackKey(t)
                else -> ""
            }
        }.thenBy {
            if (mode == 3 || mode == 7) discOf(it.value) else 0
        }.thenBy {
            if (mode == 3 || mode == 7) it.value.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE else 0
        }.thenBy {
            if (mode == 6) it.value.durationMs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE else 0L
        }.thenBy { it.index }).map { it.value }

    private fun albumTrackKey(t: PlaylistTrack): String {
        val album = t.album.trim().replace(editionSuffix, "").ifEmpty { "\uffff" }.lowercase()
        val parent = t.path.substringBeforeLast('/', "")
        val albumFolder = if (discFolder.matchEntire(parent.substringAfterLast('/')) != null)
            parent.substringBeforeLast('/', "") else parent
        return "$album\u0000${albumFolder.lowercase()}"
    }

    private fun discOf(t: PlaylistTrack): Int = t.discNumber.takeIf { it > 0 }
        ?: discFolder.matchEntire(t.path.substringBeforeLast('/', "").substringAfterLast('/'))
            ?.groupValues?.get(1)?.toIntOrNull()
        ?: 1 // single-disc albums usually have no DISCNUMBER tag
}
