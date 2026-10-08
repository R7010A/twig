package com.twig.app

import com.twig.core.XFile

/** UTF-8 extended M3U; same-source paths are relative to the destination directory. */
object PlaylistExport {
    fun render(
        dir: XFile,
        tracks: List<Pair<PlaylistTrack, XFile>>,
        connectionFor: (PlaylistTrack) -> SavedConnection? = { null },
    ): String = buildString {
        append("#EXTM3U\n")
        for ((track, file) in tracks) {
            val title = track.title.ifEmpty { track.name }
            val label = if (track.artist.isEmpty()) title else "${track.artist} - $title"
            append("#EXTINF:")
            append(if (track.durationMs > 0) track.durationMs / 1000 else -1)
            append(',').append(label.replace('\r', ' ').replace('\n', ' ')).append('\n')
            val path = entry(dir, file, track, connectionFor)
            append(path).append('\n')
        }
    }

    private fun entry(dir: XFile, file: XFile, track: PlaylistTrack, connectionFor: (PlaylistTrack) -> SavedConnection?): String {
        // Opaque document/content URIs are not slash-separated filesystem paths.
        if (dir.scheme != file.scheme || !dir.path.startsWith('/') || !file.path.startsWith('/')) {
            if (track.kind == "conn") return formatRawLocationPath(track.connLabel, file.path, connectionFor(track))
            return if (file.path.startsWith("content://")) file.path else file.toUri()
        }
        fun segments(path: String): List<String> {
            val result = ArrayList<String>()
            for (part in path.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                else -> result.add(part)
            }
            return result
        }
        val base = segments(dir.path)
        val target = segments(file.path)
        val common = base.zip(target).takeWhile { (a, b) -> a == b }.size
        val relative = (List(base.size - common) { ".." } + target.drop(common)).joinToString("/")
        // A leading '#' would be mistaken for a comment by an M3U reader.
        return if (relative.startsWith('#')) "./$relative" else relative
    }

    fun fileName(name: String): String =
        name.map { if (it < ' ' || it in "/\\:*?\"<>|") '_' else it }
            .joinToString("").trim().trimEnd('.').ifEmpty { "playlist" } + ".m3u8"
}
