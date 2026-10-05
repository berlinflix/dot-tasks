package dev.suyash.dot.core.domain.ring

import dev.suyash.dot.core.domain.model.RingMode
import java.time.Duration
import java.time.Instant

/** The phone's ringer switch, as reported by the system. */
enum class RingerMode { NORMAL, VIBRATE, SILENT }

/** Snapshot of what the user has told the phone about interruptions, taken when a reminder fires. */
data class AttentionState(
    val ringerMode: RingerMode,
    /** True when Do Not Disturb or any Mode is filtering interruptions. */
    val doNotDisturb: Boolean,
)

/** How a firing reminder is presented. */
enum class AlertStyle {
    /** Looping ringtone + vibration + full-screen call screen. */
    RING,

    /** Repeating vibration + full-screen call screen, no sound. */
    VIBRATE,

    /** Silent heads-up notification; no sound, vibration or full-screen screen. */
    QUIET,

    /** A normal one-shot notification (task set to "notify only"). */
    NOTIFY,

    /** The reminder fired too late (phone was off); show a quiet "missed" notification instead. */
    MISSED,
}

/**
 * Decides how a reminder alerts. The user's rule: never ring when the phone is on silent or in
 * Do Not Disturb — behave like an incoming call would.
 */
object RingPolicy {

    /** Reminders delivered later than this (e.g. phone was switched off) are shown as missed. */
    val MAX_LATENESS: Duration = Duration.ofMinutes(10)

    fun decide(
        mode: RingMode,
        state: AttentionState,
        scheduledAt: Instant,
        now: Instant,
    ): AlertStyle = when {
        Duration.between(scheduledAt, now) > MAX_LATENESS -> AlertStyle.MISSED
        state.doNotDisturb -> AlertStyle.QUIET
        state.ringerMode == RingerMode.SILENT -> AlertStyle.QUIET
        mode == RingMode.NOTIFY -> AlertStyle.NOTIFY
        state.ringerMode == RingerMode.VIBRATE -> AlertStyle.VIBRATE
        else -> AlertStyle.RING
    }
}
