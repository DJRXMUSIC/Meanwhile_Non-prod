package app.meanwhile.ui.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.meanwhile.domain.profile.Profile

/**
 * On-device speech recognition (spec §9.1) tuned for dosing talk: the Pixel's on-device recognizer
 * (same engine as Recorder/Gboard dictation), spoken numbers formatted as digits, the vocabulary
 * biased toward Danny's factor keywords and dose words, and longer end-of-speech silence so a pause
 * to think doesn't cut him off. The final transcript is handed back for review — never sent
 * automatically.
 */
class SpeechController(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

    fun start(biasing: List<String> = emptyList(), onFinal: (String) -> Unit) {
        error = null
        partial = ""
        if (!available) {
            error = "Speech recognition isn't available on this phone"
            return
        }
        val r = recognizer ?: create().also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(code: Int) {
                listening = false
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Offline speech model missing — download English in Settings → System → Languages → On-device speech recognition"
                    else -> "Speech error $code"
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // A thinking pause ("60 carbs… uhh… 20 fat") shouldn't end the utterance.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            if (Build.VERSION.SDK_INT >= 33) {
                // "sixty" → "60" straight from the recognizer (the router also normalizes as backup).
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                // "slept like shit" must arrive as words, not asterisks, or routing breaks.
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                if (biasing.isNotEmpty()) {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing))
                }
            }
        }
        r.startListening(intent)
    }

    /** The explicit on-device recognizer when the phone has one (Pixels do) — faster and private. */
    private fun create(): SpeechRecognizer =
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    fun stop() {
        recognizer?.stopListening()
        listening = false
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}

@Composable
fun rememberSpeechController(): SpeechController {
    val context = LocalContext.current
    val controller = remember { SpeechController(context) }
    DisposableEffect(controller) { onDispose { controller.destroy() } }
    return controller
}

/** Words worth biasing recognition toward: dosing vocabulary plus the profile's factor keywords. */
fun speechBiasing(profile: Profile): List<String> =
    (CORE_TERMS + profile.factors.flatMap { it.keywords } + profile.factors.map { it.name.lowercase() })
        .distinct().take(120)

private val CORE_TERMS = listOf(
    "carbs", "carb", "grams", "fat", "protein", "units", "unit", "insulin", "humalog", "lantus",
    "bolus", "basal", "long-acting", "rapid", "correction", "took", "injected", "liquid", "sugary",
    "and a half", "point five", "feedback", "app note", "skipped",
)

