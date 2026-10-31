package com.infinifolder.playlist

/** Compatibility names for existing container and storage callers. */
typealias PlaylistEntry = com.infinifolder.audio.playlist.PlaylistEntry

object PlaylistParser {
    fun isPlaylist(name: String, content: String = ""): Boolean =
        com.infinifolder.audio.playlist.PlaylistParser.isPlaylist(name, content)

    fun parse(name: String, content: String): List<PlaylistEntry> =
        com.infinifolder.audio.playlist.PlaylistParser.parse(name, content)

    fun calculateCddbDiscId(trackOffsetsFrames: List<Int>, leadoutFrames: Int? = null): String =
        com.infinifolder.audio.playlist.PlaylistParser.calculateCddbDiscId(trackOffsetsFrames, leadoutFrames)
}
