package com.flowstate.data

/**
 * Display names for a local track, cleaned of what MediaStore leaves behind.
 *
 * MediaStore reports a missing artist as the literal string `<unknown>`, not null, so it
 * reached every screen verbatim. And a large share of real libraries are ripped or
 * downloaded files with no artist tag and the artist in the title instead —
 * "Billy Idol - White Wedding". When, and only when, the artist tag is missing, a title
 * of that shape is split so the artist lands where the screens expect it.
 */
object TrackNames {

    /** MediaStore's placeholder for a missing tag (`MediaStore.UNKNOWN_STRING`). */
    const val UNKNOWN = "<unknown>"

    data class Names(val title: String, val artist: String)

    fun clean(rawTitle: String?, rawArtist: String?): Names {
        val title = rawTitle?.trim().orEmpty()
        val artist = rawArtist?.trim().orEmpty().takeUnless { it.isEmpty() || it == UNKNOWN }
        if (artist != null) return Names(title.ifEmpty { "Unknown track" }, artist)

        // "Artist - Title", "Artist – Title", "Artist— Title": a dash with a space on at
        // least one side, so hyphenated words ("Jay-Z", "Re-Up") are never split.
        val split = SEPARATOR.find(title)
        if (split != null) {
            val left = title.substring(0, split.range.first).trim()
            val right = title.substring(split.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return Names(right, left)
        }
        return Names(title.ifEmpty { "Unknown track" }, "")
    }

    private val SEPARATOR = Regex("""\s[-–—]\s?|[-–—]\s""")
}
