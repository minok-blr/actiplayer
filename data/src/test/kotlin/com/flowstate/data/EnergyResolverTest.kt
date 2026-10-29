package com.flowstate.data

import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.EnergySource
import com.flowstate.data.db.PoolSourceEntity
import com.flowstate.data.db.PoolSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolution order decides what actually plays, so it is tested rather than
 * desk-checked. [PoolKeys] exists precisely so these can run without an Android `Uri`.
 */
class EnergyResolverTest {

    private data class Fake(
        override val id: Long,
        override val bucketId: Long = 0,
        override val albumId: Long = 0,
        override val isPodcast: Boolean = false,
    ) : PoolKeys

    private fun pool(kind: PoolSourceKind, key: String, level: EnergyLevel) =
        PoolSourceEntity(kind, key, level, key, 0L)

    private val metalFolder = pool(PoolSourceKind.FOLDER, "10", EnergyLevel.HYPE)
    private val ambientAlbum = pool(PoolSourceKind.ALBUM, "77", EnergyLevel.CHILL)

    @Test
    fun `a pooled folder gives its tracks the pool's band`() {
        val tracks = listOf(Fake(1, bucketId = 10), Fake(2, bucketId = 10))

        val resolved = EnergyResolver.resolve(tracks, LocalEnergies(), listOf(metalFolder))

        assertEquals(2, resolved.size)
        assertTrue(resolved.all { it.band == 5 && it.source == EnergySource.POOL })
    }

    @Test
    fun `an explicit rating beats the folder the file happens to sit in`() {
        val tracks = listOf(Fake(1, bucketId = 10))
        val energies = LocalEnergies(ratings = mapOf(1L to 2))

        val resolved = EnergyResolver.resolve(tracks, energies, listOf(metalFolder))

        assertEquals(2, resolved.single().band)
        assertEquals(EnergySource.USER, resolved.single().source)
    }

    @Test
    fun `an excluded track stays out even when its folder is pooled`() {
        val tracks = listOf(Fake(1, bucketId = 10), Fake(2, bucketId = 10))
        val energies = LocalEnergies(excluded = setOf(1L))

        val resolved = EnergyResolver.resolve(tracks, energies, listOf(metalFolder))

        assertEquals(listOf(2L), resolved.map { it.track.id })
    }

    @Test
    fun `an album pool outranks the folder pool`() {
        val tracks = listOf(Fake(1, bucketId = 10, albumId = 77))

        val resolved = EnergyResolver.resolve(tracks, LocalEnergies(), listOf(metalFolder, ambientAlbum))

        assertEquals(1, resolved.single().band)
    }

    @Test
    fun `a playlist pool outranks both album and folder`() {
        val tracks = listOf(Fake(1, bucketId = 10, albumId = 77))
        val playlist = pool(PoolSourceKind.PLAYLIST, "5", EnergyLevel.MID)

        val resolved = EnergyResolver.resolve(
            tracks,
            LocalEnergies(),
            listOf(metalFolder, ambientAlbum, playlist),
            playlistMembers = mapOf("5" to setOf(1L)),
        )

        assertEquals(3, resolved.single().band)
    }

    @Test
    fun `tracks that are neither rated nor pooled never enter a session`() {
        val tracks = listOf(Fake(1, bucketId = 10), Fake(99, bucketId = 999))

        val resolved = EnergyResolver.resolve(tracks, LocalEnergies(), listOf(metalFolder))
        val unsorted = EnergyResolver.unsorted(tracks, LocalEnergies(), listOf(metalFolder))

        assertEquals(listOf(1L), resolved.map { it.track.id })
        assertEquals(listOf(99L), unsorted.map { it.id })
    }

    @Test
    fun `a podcast never plays, even from a pooled folder`() {
        val tracks = listOf(
            Fake(1, bucketId = 10),
            Fake(2, bucketId = 10, isPodcast = true),
        )

        val resolved = EnergyResolver.resolve(tracks, LocalEnergies(), listOf(metalFolder))

        assertEquals(listOf(1L), resolved.map { it.track.id })
    }

    @Test
    fun `a podcast someone rated still never plays`() {
        val tracks = listOf(Fake(7, isPodcast = true))
        val energies = LocalEnergies(ratings = mapOf(7L to 5))

        assertEquals(emptyList<Long>(), EnergyResolver.resolve(tracks, energies, emptyList()).map { it.track.id })
    }

    @Test
    fun `podcasts are not counted as waiting to be sorted`() {
        val tracks = listOf(Fake(1), Fake(2, isPodcast = true))

        val unsorted = EnergyResolver.unsorted(tracks, LocalEnergies(), emptyList())

        assertEquals(listOf(1L), unsorted.map { it.id })
    }

    @Test
    fun `bands group into the levels a user sees`() {
        // Plan §3.1: Chill = 1-2, Mid = 3, Hype = 4-5.
        assertEquals(EnergyLevel.CHILL, EnergyResolver.levelOf(1))
        assertEquals(EnergyLevel.CHILL, EnergyResolver.levelOf(2))
        assertEquals(EnergyLevel.MID, EnergyResolver.levelOf(3))
        assertEquals(EnergyLevel.HYPE, EnergyResolver.levelOf(4))
        assertEquals(EnergyLevel.HYPE, EnergyResolver.levelOf(5))
    }

    @Test
    fun `pool counts split by level`() {
        val tracks = listOf(Fake(1, bucketId = 10), Fake(2, albumId = 77), Fake(3, bucketId = 10))

        val counts = EnergyResolver.trackCounts(
            tracks,
            LocalEnergies(),
            listOf(metalFolder, ambientAlbum),
        )

        assertEquals(2, counts[EnergyLevel.HYPE])
        assertEquals(0, counts[EnergyLevel.MID])
        assertEquals(1, counts[EnergyLevel.CHILL])
    }
}
