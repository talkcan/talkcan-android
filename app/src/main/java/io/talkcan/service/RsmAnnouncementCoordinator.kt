package io.talkcan.service

import io.talkcan.audio.HostAudioFeedback
import io.talkcan.audio.NavigationSynthesisResult
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.RecordedPcm
import io.talkcan.model.ChannelCatalogueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Focused owner of RSM navigation text announcement and error-beep
 * orchestration.
 *
 * The coordinator resolves announcement text from the current catalogue,
 * delegates synthesis to the existing [NavigationTtsEngine] latest-wins API
 * (it introduces no competing request queue), routes successful PCM through
 * the host-audio playback boundary, and forwards every synthesis result to
 * the bootstrap result callback for state-loss classification.
 *
 * It holds no service reference, does not mutate [io.talkcan.model.AppState],
 * and owns no mutable state beyond the coroutine launches it issues into
 * [scope]. Latest-wins supersession is delegated entirely to
 * [NavigationTtsEngine]; this coordinator adds no additional queue.
 *
 * @param scope the service-lifetime coroutine scope used to launch
 *   announcement and error-beep requests.
 * @param catalogue accessor returning the current
 *   [ChannelCatalogueSnapshot] for text resolution.
 * @param navigationEngine accessor returning the current
 *   [NavigationTtsEngine], or `null` when navigation audio is unavailable.
 * @param playPcm the host-audio playback boundary. Receives a
 *   [RecordedPcm] and returns a [HostPlaybackResult]. The service wires
 *   this to `hostAudioCoordinator.play(recording) { playbackRouteResolver.strategyFor(InputMode.Work) }`.
 * @param onSynthesisResult the bootstrap result callback, invoked with every
 *   [NavigationSynthesisResult] produced by the engine. The service wires
 *   this to `bootstrapCoordinator::onNavigationSynthesisResult`.
 */
internal class RsmAnnouncementCoordinator(
    private val scope: CoroutineScope,
    private val catalogue: () -> ChannelCatalogueSnapshot,
    private val navigationEngine: () -> NavigationTtsEngine?,
    private val playPcm: suspend (RecordedPcm) -> HostPlaybackResult,
    private val onSynthesisResult: (NavigationSynthesisResult) -> Unit,
) {

    /**
     * Requests a navigation announcement for [key].
     *
     * Resolves text via [resolveRsmAnnouncementText] against the current
     * catalogue. If the key is invalid, unknown, or references a removed
     * channel, no synthesis, playback, or result callback is started. If the
     * navigation engine is unavailable, no work is started.
     *
     * When both text and engine are available, synthesis is delegated to
     * [NavigationTtsEngine.request] exactly once. Successful PCM is played
     * through [playPcm]; the synthesis result is forwarded to
     * [onSynthesisResult]. No error-beep fallback is produced on
     * announcement failure — that preserves the existing service behavior.
     */
    fun announce(key: String) {
        val text = resolveRsmAnnouncementText(key, catalogue()) ?: return
        val engine = navigationEngine() ?: return
        scope.launch {
            val result = engine.request(text) { recording ->
                playPcm(recording)
            }
            onSynthesisResult(result)
        }
    }

    /**
     * Requests RSM error feedback (a short beep).
     *
     * When the navigation engine is available, the error-beep PCM is
     * submitted through [NavigationTtsEngine.requestPcm] so it follows the
     * same latest-wins ownership as text announcements; the result is
     * forwarded to [onSynthesisResult].
     *
     * When the navigation engine is unavailable, the error-beep PCM is
     * played directly through [playPcm] without a synthesis result
     * callback — preserving the existing host-audio fallback path.
     */
    fun announceErrorBeep() {
        val recording = HostAudioFeedback.errorBeep()
        val engine = navigationEngine()
        if (engine == null) {
            scope.launch {
                playPcm(recording)
            }
        } else {
            scope.launch {
                val result = engine.requestPcm(recording) { pcm ->
                    playPcm(pcm)
                }
                onSynthesisResult(result)
            }
        }
    }
}