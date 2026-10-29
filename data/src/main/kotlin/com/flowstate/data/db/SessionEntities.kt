package com.flowstate.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowstate.data.SourceKind
import com.flowstate.engine.Activity
import com.flowstate.engine.RideState

/**
 * One recorded session (plan §3.4).
 *
 * Wall-clock timestamps are stored for display and grouping; everything *inside* a
 * session is an offset from its start, because the engine's clock is monotonic and must
 * stay that way.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activity: Activity,
    val source: SourceKind,
    val startedAtEpochMs: Long,
    /** Null while the session is still running; set when it ends. */
    val endedAtEpochMs: Long? = null,
    val durationMs: Long = 0,
)

enum class SessionEventKind {
    /** The engine changed mode. */
    MODE,

    /** A different track started. */
    TRACK,

    /**
     * The athlete overrode the engine. These are the honest quality metric: every one is
     * the engine getting it wrong and being corrected by hand.
     */
    OVERRIDE,

    /** A periodic HR/speed sample for the recap timeline. */
    SAMPLE,
}

/**
 * One thing that happened during a session. A single table with a [kind] discriminator
 * rather than four narrow ones: the recap reads the whole timeline in order, so keeping
 * it in one place is what makes that a single query.
 */
@Entity(
    tableName = "session_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** Milliseconds since the session started. */
    val tMs: Long,
    val kind: SessionEventKind,
    val state: RideState? = null,
    val bandLow: Int? = null,
    val bandHigh: Int? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val trackBand: Int? = null,
    /** MediaStore id of the track, for cover art in the recap. */
    val trackMediaId: Long? = null,
    /**
     * `OverrideMode` lives in `:session`, which depends on `:data` and not the other way
     * round, so it is stored by name rather than inverting the module graph for one column.
     */
    val overrideMode: String? = null,
    val hrBpm: Int? = null,
    val speedKmh: Double? = null,
)
