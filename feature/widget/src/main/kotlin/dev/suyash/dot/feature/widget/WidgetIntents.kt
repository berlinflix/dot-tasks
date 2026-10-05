package dev.suyash.dot.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Explicit intents only (no compile-time dependency on other feature modules). */
internal object WidgetIntents {
    private const val VOICE_ACTIVITY = "dev.suyash.dot.feature.voice.VoiceCaptureActivity"
    const val EXTRA_OPEN_TASK_ID = "dev.suyash.dot.extra.OPEN_TASK_ID"

    fun voice(context: Context): Intent =
        Intent().setComponent(ComponentName(context.packageName, VOICE_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    fun openApp(context: Context, taskId: String? = null): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (taskId != null) launch.putExtra(EXTRA_OPEN_TASK_ID, taskId)
        return launch
    }
}
