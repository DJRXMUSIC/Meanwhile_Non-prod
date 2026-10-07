package app.meanwhile.ui.speech

import app.meanwhile.log.AppLog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.meanwhile.data.input.VoiceDetails
import app.meanwhile.domain.profile.Profile

/** Where the mic is, shown on screen so Danny always knows whether he's being heard (1.4). */
enum class MicState {
    IDLE,

    /** Tapped; the recognizer is starting up. */
    STARTING,

    /** Ready — speak now. */
    LISTENING,

    /** Speech detected; the words appear as they're recognized. */
    HEARING,

    /** Speech ended; the final transcript is on its way. */
    PROCESSING,
}

/**
 * On-device speech recognition (spec §9.1) tuned for dosing talk: the Pixel's on-device recognizer
 * (same engine as Recorder/Gboard dictation), spoken numbers formatted as digits, the vocabulary
 * biased toward Danny's factor keywords and dose words, and longer end-of-speech silence so a pause
 * to think doesn't cut him off. Every state change is visible ([state], live [level] and [partial]),
 * and [start]'s callback lets the screen tick the phone so the mic is felt as well as seen.
 */
class SpeechController(private val context: Context) {
    var state by mutableStateOf(MicState.IDLE)
        private set
    var partial by mutableStateOf("")
        private set

    /** Microphone input level 0–1 while listening (proof the mic hears something). */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val active: Boolean get() = state != MicState.IDLE

    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L

    /** False once the on-device recognizer failed for lack of a model: the standard one is used instead. */
    private var preferOnDevice = true

    /** The words heard so far this session — never thrown away by a late error or an empty result. */
    private var heardSoFar = ""
    private var delivered = true
    private var finish: ((String, List<String>, List<Float>, String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val deliverHeard = Runnable { finish?.invoke(heardSoFar, emptyList(), emptyList(), "stopped") }

    val available: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts listening. [onFinal] receives the best transcript and everything else the recognizer
     * offered (alternatives, confidences, how long it listened); [onStateChange] is called on every state
     * change and with `null` on an error.
     *
     * 2.0 (Danny: "it captures what I'm saying and then immediately says it hears no sound and deletes
     * it"): some recognizers end a session with "no match" / "no speech", or an empty final result, after
     * they already showed the words. Whatever was heard is now delivered in those cases, and anything
     * arriving after the words were delivered is ignored.
     */
    fun start(
        biasing: List<String> = emptyList(),
        onStateChange: (MicState?) -> Unit = {},
        onFinal: (String, VoiceDetails) -> Unit,
    ) {
        error = null
        partial = ""
        level = 0f
        heardSoFar = ""
        delivered = false
        main.removeCallbacks(deliverHeard)
        if (!available) {
            error = "Speech recognition isn't available on this phone"
            onStateChange(null)
            return
        }
        fun move(to: MicState) {
            if (state != to) {
                state = to
                onStateChange(to)
            }
        }
        val r = recognizer ?: create().also { recognizer = it }
        startedAt = System.currentTimeMillis()
        finish = { text, alternatives, scores, how ->
            if (!delivered && text.isNotBlank()) {
                delivered = true
                main.removeCallbacks(deliverHeard)
                level = 0f
                state = MicState.IDLE
                partial = ""
                error = null
                if (how != "results") AppLog.i("Speech", "delivered what was heard ($how) after ${System.currentTimeMillis() - startedAt} ms")
                onStateChange(MicState.IDLE)
                onFinal(text.trim(), VoiceDetails(alternatives = alternatives, confidences = scores, listenedMs = System.currentTimeMillis() - startedAt, language = "en-US"))
            }
        }
        move(MicState.STARTING)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = move(MicState.LISTENING)

            override fun onBeginningOfSpeech() = move(MicState.HEARING)

            override fun onRmsChanged(rmsdB: Float) {
                // Typically −2 dB (silence) to 10 dB (speaking up).
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                level = 0f
                move(MicState.PROCESSING)
            }

            override fun onError(code: Int) {
                if (delivered) {
                    AppLog.i("Speech", "ignored recognizer error $code (${name(code)}) after the words were delivered")
                    return
                }
                AppLog.w("Speech", "recognizer error $code (${name(code)}) after ${System.currentTimeMillis() - startedAt} ms, heard so far: ${heardSoFar.length} chars")
                // Words were heard: they count, whatever the recognizer says at the end.
                if (heardSoFar.isNotBlank() && code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    finish?.invoke(heardSoFar, emptyList(), emptyList(), "error ${name(code)}")
                    return
                }
                level = 0f
                state = MicState.IDLE
                main.removeCallbacks(deliverHeard)
                val switched = preferOnDevice && code in MODEL_ERRORS
                if (switched) {
                    // No on-device model for this language: use the phone's standard recognizer next time.
                    preferOnDevice = false
                    recognizer?.destroy()
                    recognizer = null
                    AppLog.w("Speech", "on-device recognizer unavailable — switching to the standard recognizer")
                }
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch any words — tap the mic and try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything — tap the mic and speak right away"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_AUDIO -> "The microphone couldn't record — is another app using it?"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy — try again in a second"
                    SpeechRecognizer.ERROR_CLIENT -> "Stopped"
                    in MODEL_ERRORS -> if (switched) {
                        "The on-device speech model isn't ready — tap the mic again to use the phone's standard recognizer"
                    } else {
                        "Speech service error (${name(code)}) — check the connection and try again"
                    }
                    else -> "Speech error $code (${name(code)}) — try again"
                }
                onStateChange(null)
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()
                val best = texts.firstOrNull()?.trim().orEmpty()
                when {
                    delivered -> Unit
                    best.isNotBlank() -> finish?.invoke(best, texts.drop(1), scores, "results")
                    heardSoFar.isNotBlank() -> finish?.invoke(heardSoFar, emptyList(), emptyList(), "empty final result")
                    else -> {
                        level = 0f
                        state = MicState.IDLE
                        error = "Didn't catch any words — tap the mic and try again"
                        AppLog.w("Speech", "recognizer returned no words")
                        onStateChange(null)
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (delivered) return
                val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (words.isBlank()) return // an empty update never wipes what was already heard
                partial = words
                heardSoFar = words
                if (state == MicState.STARTING || state == MicState.LISTENING) move(MicState.HEARING)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // The standard recognizer (fallback) may use the network when there's no offline model.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            // Alternatives are kept in the conversation log ("six" vs "sex" vs "6").
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            state = MicState.IDLE
            delivered = true
            error = "Couldn't start the microphone: ${e.message ?: e::class.java.simpleName}"
            AppLog.e("Speech", "startListening failed", e)
            onStateChange(null)
        }
    }

    /** The explicit on-device recognizer when the phone has one (Pixels do) — faster and private. */
    private fun create(): SpeechRecognizer =
        if (preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /**
     * Finish now: whatever was said so far is transcribed and delivered. If the recognizer doesn't
     * answer within [STOP_GRACE_MS], the words already on screen are sent as they are.
     */
    fun stop() {
        if (state == MicState.IDLE) return
        recognizer?.stopListening()
        level = 0f
        if (heardSoFar.isBlank() && (state == MicState.STARTING || state == MicState.LISTENING)) {
            recognizer?.cancel()
            delivered = true
            state = MicState.IDLE
            return
        }
        state = MicState.PROCESSING
        main.removeCallbacks(deliverHeard)
        main.postDelayed(deliverHeard, STOP_GRACE_MS)
    }

    fun destroy() {
        main.removeCallbacks(deliverHeard)
        delivered = true
        recognizer?.destroy()
        recognizer = null
        state = MicState.IDLE
    }

    private companion object {
        const val STOP_GRACE_MS = 2500L
        val MODEL_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        )
    }

    private fun name(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        else -> "other"
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
    "and a half", "point five", "feedback", "app note", "skipped", "never mind", "took it", "BG",
    "blood sugar", "what should I do",
)
