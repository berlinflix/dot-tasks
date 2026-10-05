package dev.suyash.dot.feature.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry points from outside the app: "Share → Dot" and the launcher shortcuts. It shows nothing
 * itself and hands over to the (not exported) capture sheet, where the user confirms before anything
 * is saved, so another app can never add tasks silently. Only plain text is read, never files.
 */
class QuickCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = when (intent?.action) {
            Intent.ACTION_SEND -> VoiceCaptureActivity.typingIntent(this, sharedText(intent))
            ACTION_VOICE_TASK -> VoiceCaptureActivity.intent(this)
            ACTION_TYPE_TASK -> VoiceCaptureActivity.typingIntent(this)
            else -> null
        }
        next?.let(::startActivity)
        finish()
    }

    /** "Subject — text" from a text share, flattened to one line and capped. */
    private fun sharedText(intent: Intent): String = runCatching {
        if (intent.type?.startsWith("text/") != true) return@runCatching ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
        val combined = if (subject.isNotEmpty() && !text.contains(subject)) "$subject $text" else text.ifEmpty { subject }
        combined
            .filter { !it.isISOControl() || it == '\n' || it == '\t' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_LENGTH)
    }.getOrDefault("") // a malformed extra from another app is simply ignored

    companion object {
        const val ACTION_VOICE_TASK = "dev.suyash.dot.action.VOICE_TASK"
        const val ACTION_TYPE_TASK = "dev.suyash.dot.action.TYPE_TASK"
        private const val MAX_LENGTH = 500
    }
}
