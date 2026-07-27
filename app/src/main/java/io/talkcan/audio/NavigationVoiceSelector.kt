package io.talkcan.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

// ---------------------------------------------------------------------------
// Voice selection
// ---------------------------------------------------------------------------

/**
 * The result of deterministic offline English voice discovery and selection.
 */
internal sealed interface VoiceSelectionResult {

    /**
     * A valid offline English voice was found and [setVoice] returned
     * [TextToSpeech.SUCCESS].
     */
    data class Selected(val voice: Voice) : VoiceSelectionResult

    /** No valid offline English voice was discovered. */
    data object Missing : VoiceSelectionResult

    /**
     * [TextToSpeech.setVoice] returned [TextToSpeech.ERROR] for the selected
     * voice. The caller SHALL shut down the instance.
     */
    data object SelectionFailed : VoiceSelectionResult
}

/**
 * Discover and select a deterministic installed offline English voice from
 * [TextToSpeech.getVoices] after `onInit(SUCCESS)`.
 *
 * A voice is a valid offline candidate only when all of the following hold:
 *
 * 1. The voice locale language is `en`.
 * 2. [Voice.isNetworkConnectionRequired] returns `false`.
 * 3. [Voice.getFeatures] does not contain
 *    [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED].
 * 4. [TextToSpeech.isLanguageAvailable] for the voice locale returns
 *    `LANG_AVAILABLE`, `LANG_COUNTRY_AVAILABLE`, or `LANG_COUNTRY_VAR_AVAILABLE`.
 *
 * Valid candidates are sorted by:
 * 1. [Voice.getLatency] ascending.
 * 2. [Voice.getQuality] descending.
 * 3. BCP-47 locale tag ascending.
 * 4. Voice name ascending.
 *
 * The first voice after this sort is selected via [TextToSpeech.setVoice].
 * If `setVoice` returns [TextToSpeech.ERROR],
 * [VoiceSelectionResult.SelectionFailed] is returned.
 *
 * This function does NOT attempt to install or download voice data, and
 * does NOT call `synthesizeToFile` — the real synthesis probe (D3) is a
 * separate step.
 *
 * @param tts the initialized `TextToSpeech` instance (after `onInit(SUCCESS)`).
 * @return the selection result. The caller is responsible for shutting down
 *   the instance on [VoiceSelectionResult.SelectionFailed].
 */
internal fun selectOfflineEnglishVoice(tts: TextToSpeech): VoiceSelectionResult {
    val voices = tts.getVoices() ?: return VoiceSelectionResult.Missing

    val candidates = voices
        .filter { voice ->
            val locale = voice.locale
            if (locale.language != "en") return@filter false

            if (voice.isNetworkConnectionRequired) return@filter false

            val features = voice.features
            if (features != null &&
                features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            ) {
                return@filter false
            }

            val langResult = tts.isLanguageAvailable(locale)
            val langOk = langResult == TextToSpeech.LANG_AVAILABLE ||
                langResult == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                langResult == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            if (!langOk) return@filter false

            true
        }
        .sortedWith(
            compareBy<Voice> { it.latency }
                .thenByDescending { it.quality }
                .thenBy { it.locale.toLanguageTag() }
                .thenBy { it.name },
        )

    if (candidates.isEmpty()) return VoiceSelectionResult.Missing

    val selected = candidates.first()
    val rc = tts.setVoice(selected)
    return if (rc == TextToSpeech.ERROR) {
        VoiceSelectionResult.SelectionFailed
    } else {
        VoiceSelectionResult.Selected(selected)
    }
}
