package dev.suyash.dot.feature.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.suyash.dot.feature.reminders.notify.ReminderNotifier
import javax.inject.Inject

/** Everything reminders need from the user, with deep links to the matching system settings page. */
data class ReminderReadiness(
    val notifications: Boolean,
    val ringChannelOn: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
) {
    /** Reminders will fire on time and be visible. */
    val canRemind: Boolean get() = notifications && exactAlarms
    val allGood: Boolean get() = notifications && ringChannelOn && exactAlarms && fullScreen
}

class ReminderPermissions @Inject constructor(@ApplicationContext private val context: Context) {

    fun readiness(): ReminderReadiness {
        val notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringChannel = manager.getNotificationChannel(ReminderNotifier.CHANNEL_RING)
        return ReminderReadiness(
            notifications = notifications && manager.areNotificationsEnabled(),
            ringChannelOn = ringChannel == null || ringChannel.importance != NotificationManager.IMPORTANCE_NONE,
            exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent(),
        )
    }

    /** "Alarms & reminders" special access for this app. */
    fun exactAlarmSettings(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    /** Full-screen notifications special access (Android 14+). */
    fun fullScreenSettings(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            appNotificationSettings()
        }

    fun appNotificationSettings(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** The ringing channel's own page, where the user can pick a different ringtone. */
    fun ringChannelSettings(): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotifier.CHANNEL_RING)
}
