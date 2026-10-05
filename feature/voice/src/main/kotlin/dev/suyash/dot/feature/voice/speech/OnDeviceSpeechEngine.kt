package dev.suyash.dot.feature.voice.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    /** Input level in dB (roughly -2…10), for the level meter. */
    data class Level(val rmsDb: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Failed(val reason: SpeechFailure) : SpeechEvent
}

enum class SpeechFailure { NO_MATCH, NO_SPEECH, LANGUAGE_UNAVAILABLE, PERMISSION, BUSY, UNAVAILABLE, OTHER }

/** Whether this phone can transcribe [locale] fully offline right now. */
enum class OnDeviceSupport { READY, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

/**
 * Speech-to-text that runs **entirely on the device** (`createOnDeviceSpeechRecognizer`).
 * There is intentionally no cloud fallback: if on-device recognition isn't available the user types.
 * The recognizer streams audio internally; nothing is written to disk by this app.
 *
 * SpeechRecognizer must be used from the main thread — collect [listen] on Dispatchers.Main.
 */
class OnDeviceSpeechEngine @Inject constructor(@ApplicationContext private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun support(locale: Locale): OnDeviceSupport {
        if (!isAvailable()) return OnDeviceSupport.UNSUPPORTED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return OnDeviceSupport.UNKNOWN
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(
                    recognizerIntent(locale),
                    mainExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val tag = locale.toLanguageTag()
                            val result = when {
                                support.installedOnDeviceLanguages.any { it.equals(tag, ignoreCase = true) } -> OnDeviceSupport.READY
                                support.supportedOnDeviceLanguages.any { it.equals(tag, ignoreCase = true) } -> OnDeviceSupport.DOWNLOADABLE
                                else -> OnDeviceSupport.UNSUPPORTED
                            }
                            if (cont.isActive) cont.resume(result)
                        }

                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(OnDeviceSupport.UNKNOWN)
                        }
                    },
                )
            }
        } finally {
            recognizer.destroy()
        }
    }

    /** Asks the system to download the offline model for [locale] (Android 13+). */
    fun requestModelDownload(locale: Locale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isAvailable()) return
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            recognizer.triggerModelDownload(recognizerIntent(locale))
        } finally {
            recognizer.destroy()
        }
    }

    /** One utterance: emits levels and partial text, then a single [SpeechEvent.Final] or [SpeechEvent.Failed]. */
    fun listen(locale: Locale): Flow<SpeechEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechEvent.Level(rmsdB))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestText()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.bestText()
                trySend(if (text.isNullOrBlank()) SpeechEvent.Failed(SpeechFailure.NO_MATCH) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Failed(mapError(error)))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognizerIntent(locale))
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private fun recognizerIntent(locale: Locale): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun mainExecutor(): Executor = context.mainExecutor

    private fun Bundle?.bestText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun mapError(error: Int): SpeechFailure = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechFailure.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechFailure.BUSY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure.LANGUAGE_UNAVAILABLE
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
        -> SpeechFailure.UNAVAILABLE
        else -> SpeechFailure.OTHER
    }
}
