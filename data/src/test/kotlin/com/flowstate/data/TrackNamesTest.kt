package com.flowstate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNamesTest {

    @Test
    fun `a real artist tag is kept and the title left alone`() {
        val n = TrackNames.clean("Bleed It Out - Remastered", "Linkin Park")

        assertEquals(TrackNames.Names("Bleed It Out - Remastered", "Linkin Park"), n)
    }

    @Test
    fun `MediaStore's unknown placeholder never reaches the screen`() {
        assertEquals("", TrackNames.clean("FlowTest Hype A", "<unknown>").artist)
    }

    @Test
    fun `an untagged artist-dash-title file is split`() {
        val n = TrackNames.clean("Billy Idol - White Wedding", "<unknown>")

        assertEquals(TrackNames.Names("White Wedding", "Billy Idol"), n)
    }

    @Test
    fun `a dash tight on one side still splits`() {
        val n = TrackNames.clean("Bullet For My Valentine- Hit The Floor", null)

        assertEquals(TrackNames.Names("Hit The Floor", "Bullet For My Valentine"), n)
    }

    @Test
    fun `hyphenated words are never split`() {
        val n = TrackNames.clean("Re-Up", "<unknown>")

        assertEquals(TrackNames.Names("Re-Up", ""), n)
    }

    @Test
    fun `only the first separator splits, so the title keeps its own dashes`() {
        val n = TrackNames.clean("Artist - Song - Live", "<unknown>")

        assertEquals(TrackNames.Names("Song - Live", "Artist"), n)
    }
}
