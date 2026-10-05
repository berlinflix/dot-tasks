package dev.suyash.dot.feature.reminders

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.suyash.dot.core.domain.ring.AttentionState
import dev.suyash.dot.core.domain.ring.RingerMode
import javax.inject.Inject

/** Reads the ringer switch and Do Not Disturb state. Neither needs a permission. */
class AttentionStateReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun read(): AttentionState = read(context)

    companion object {
        fun read(context: Context): AttentionState {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val ringer = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                else -> RingerMode.NORMAL
            }
            // Any filter other than "all" means DND or a Mode (Bedtime, Driving…) is active.
            val filter = notifications.currentInterruptionFilter
            val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            return AttentionState(ringerMode = ringer, doNotDisturb = dnd)
        }
    }
}
