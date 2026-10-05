package dev.suyash.dot.feature.reminders.alarm

import android.content.Context

/**
 * A tiny mirror of upcoming reminder *times* in device-protected storage, readable before the user
 * unlocks after a reboot. It deliberately holds **times only — never task titles** — because this
 * storage is not protected by the user's lock-screen credential.
 */
class DirectBootStore(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("dot_direct_boot", Context.MODE_PRIVATE)

    fun saveUpcoming(timesMillis: List<Long>) {
        prefs.edit().putString(KEY_UPCOMING, timesMillis.sorted().take(MAX_TIMES).joinToString(",")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_UPCOMING).apply()
    }

    fun upcoming(): List<Long> =
        prefs.getString(KEY_UPCOMING, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    /** The earliest stored time (may be in the past if it fired while the phone was off). */
    fun next(): Long? = upcoming().firstOrNull()

    /** Drops every time at or before [nowMillis] and returns the next remaining one. */
    fun dropThrough(nowMillis: Long): Long? {
        val remaining = upcoming().filter { it > nowMillis }
        saveUpcoming(remaining)
        return remaining.firstOrNull()
    }

    /** Records that a generic ("unlock to see") ring happened before unlock, so we don't ring twice. */
    fun markGenericRing(atMillis: Long) {
        prefs.edit().putLong(KEY_GENERIC_RING, atMillis).apply()
    }

    fun takeGenericRing(): Long? {
        val value = prefs.getLong(KEY_GENERIC_RING, 0L).takeIf { it > 0L }
        if (value != null) prefs.edit().remove(KEY_GENERIC_RING).apply()
        return value
    }

    private companion object {
        const val KEY_UPCOMING = "upcoming_alarm_times"
        const val KEY_GENERIC_RING = "generic_ring_at"
        const val MAX_TIMES = 16
    }
}
