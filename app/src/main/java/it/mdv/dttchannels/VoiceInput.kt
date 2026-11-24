package it.mdv.dttchannels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * In-app speech recognition (Google's recogniser on the box, no activity switch, so the picture keeps playing).
 * One session at a time; the recogniser is created per session and destroyed after it (nothing held in between).
 */
class VoiceInput(private val ctx: Context, private val state: LiveState) : RecognitionListener {
    private var sr: SpeechRecognizer? = null

    val active: Boolean get() = sr != null

    fun start() {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { state.onVoiceError("Riconoscimento vocale non disponibile"); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(this)
            it.startListening(intent)
        }
        state.onVoiceStart()
    }

    /** Push-to-talk release: stop recording and recognise what was said so far. */
    fun finish() { sr?.stopListening() }

    fun cancel() { sr?.run { setRecognitionListener(null); destroy() }; sr = null }

    override fun onPartialResults(b: Bundle) {
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(state::onVoicePartial)
    }

    override fun onResults(b: Bundle) {
        val list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        Log.i(LiveActivity.TAG, "voice: $list")
        cancel()
        state.onVoiceResults(list)
    }

    override fun onError(error: Int) {
        Log.i(LiveActivity.TAG, "voice error $error")
        cancel()
        state.onVoiceError(when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Non ho sentito un canale, riprova"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "Riconoscimento vocale non raggiungibile"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Serve il permesso del microfono"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> "Microfono occupato, riprova"
            else -> "Riconoscimento vocale non riuscito"
        })
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) { state.onVoiceLevel(rmsdB) }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
