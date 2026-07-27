package io.talkcan.service

/**
 * Pure, framework-free now-playing metadata projection for the Android Auto
 * Media now-playing card (see design D7 / `android-auto-virtual-ptt` and
 * `car-media-channel-browse` specs).
 *
 * Build with [buildCarNowPlayingMetadata]; the [CarMediaSessionService]
 * imperative shell maps this data class to a `android.media.MediaMetadata`
 * (loading the drawable to a `Bitmap`).
 *
 * Subtitle rule (per spec `car-media-channel-browse` "Now-playing subtitle
 * carries state pill and pending summary" and design D7):
 *   subtitle = "<state pill>"                    if pendingCount <= 0
 *              "<pill> · <N> pending"            if pendingCount  > 0 and fits in 40 chars
 *              "<pill>"                          otherwise (truncate pending portion first)
 *
 * Title is the active channel's display name when the channel is known, else
 * the not-ready fallback title supplied by the caller. Artist is fixed to the
 * app name (Talkcan) per spec.
 *
 * [drawableResId] is one of the four state-tinted `car_art_*` drawables
 * (see `app/src/main/res/drawable/car_art_*.xml`).
 */
internal data class CarNowPlayingMetadata(
    val title: String,
    val artist: String,
    val subtitle: String,
    val drawableResId: Int,
)

/** Four state-tinted drawable resource ids wired by `CarMediaSessionService`. */
internal data class CarNowPlayingDrawables(
    val notReadyResId: Int,
    val readyResId: Int,
    val recordingResId: Int,
    val finalizingResId: Int,
)

internal const val CAR_NOW_PLAYING_SUBTITLE_LIMIT = 40
internal const val CAR_NOW_PLAYING_PENDING_PREFIX_SEPARATOR = " · "

/**
 * Pure builder for [CarNowPlayingMetadata]. Two-string + int + res-id inputs
 * only; no `Context`/`Resources` required, fully unit-testable.
 *
 * @param activeChannelName display name of the currently active channel, or
 *   `null` when the channel is not known (e.g. NotReady / no configured
 *   channel) — uses [notReadyFallbackTitle] as the title in that case.
 * @param state live PTT state driving pill + bitmap.
 * @param pendingCount pending unheard backlog for the active channel (0 today
 *   until inbound backlog tracking lands); renders the "<count> pending"
 *   suffix when greater than zero and truncates the suffix when overflowing
 *   the 40-char subtitle budget.
 * @param appArtist constant string for METADATA_KEY_ARTIST (e.g. app name).
 * @param notReadyFallbackTitle fallback title when [activeChannelName] is null
 *   (e.g. `car_media_not_ready_title` resource string).
 * @param drawables wired by the caller from real `R.drawable` resource ids.
 */
internal fun buildCarNowPlayingMetadata(
    activeChannelName: String?,
    state: CarMediaPttState,
    pendingCount: Int,
    appArtist: String,
    notReadyFallbackTitle: String,
    drawables: CarNowPlayingDrawables,
): CarNowPlayingMetadata {
    val title = activeChannelName ?: notReadyFallbackTitle
    val pill = statePillText(state)
    return CarNowPlayingMetadata(
        title = title,
        artist = appArtist,
        subtitle = appendPending(pill, pendingCount),
        drawableResId = stateDrawable(state, drawables),
    )
}

internal fun statePillText(state: CarMediaPttState): String = when (state) {
    CarMediaPttState.NotReady -> "NOT READY"
    CarMediaPttState.Ready -> "ACTIVE"
    CarMediaPttState.Recording -> "RECORDING"
    CarMediaPttState.Finalizing -> "FINALIZING"
}

internal fun stateDrawable(
    state: CarMediaPttState,
    drawables: CarNowPlayingDrawables,
): Int = when (state) {
    CarMediaPttState.NotReady -> drawables.notReadyResId
    CarMediaPttState.Ready -> drawables.readyResId
    CarMediaPttState.Recording -> drawables.recordingResId
    CarMediaPttState.Finalizing -> drawables.finalizingResId
}

/**
 * Appends the compact pending summary to the state pill, truncating the pending
 * portion first when the 40-char subtitle budget is exceeded (see design D7 /
 * spec `car-media-channel-browse` "Now-playing subtitle carries state pill and
 * pending summary").
 */
internal fun appendPending(pill: String, pendingCount: Int): String {
    if (pendingCount <= 0) return pill
    val full = pill + CAR_NOW_PLAYING_PENDING_PREFIX_SEPARATOR + "$pendingCount pending"
    if (full.length <= CAR_NOW_PLAYING_SUBTITLE_LIMIT) return full
    // Truncate the pending portion first: pill is the inviolable prefix and the
    // pending summary is the truncated suffix when space is tight.
    return pill
}