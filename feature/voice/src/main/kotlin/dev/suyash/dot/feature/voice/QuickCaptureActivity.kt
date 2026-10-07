package dev.suyash.dot.feature.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Share → Dot": the only screen other apps can open. It shows nothing itself and hands the text to
 * the (not exported) capture sheet in typing mode, where the user confirms before anything is saved,
 * so another app can never add tasks silently or turn on the microphone. Only plain text is read.
 */
class QuickCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 12–13 deliver explicit intents even when no intent filter matches: accept only shares.
        if (intent?.action == Intent.ACTION_SEND) startActivity(VoiceCaptureActivity.typingIntent(this, sharedText(intent)))
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

    private companion object {
        const val MAX_LENGTH = 500
    }
}
