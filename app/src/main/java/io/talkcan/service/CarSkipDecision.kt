package io.talkcan.service

/**
 * The set of contextual actions the steering-wheel `Next`/`Prev` controls map
 * to on the Talkcan car `MediaSession` (see design D4 / spec
 * `car-contextual-skip-controls`).
 *
 * - [NoOp]              — the control is a no-op for the current state.
 * - [NextChannel]       — advance the active channel by stable channel order
 *   (radio-dial; saturates at bounds, no wraparound).
 * - [PrevChannel]       — retreat the active channel by stable channel order
 *   (saturates at the first channel).
 * - [SkipMessage]       — skip the currently-playing inbound message and
 *   advance to the next queued message if any (Future wiring: pending backlog
 *   tracking, see [PttForegroundService.skipCurrentMessage]).
 * - [ReplayMessage]     — replay the last heard inbound message on the active
 *   channel (Future wiring: last-heard message state).
 */
internal enum class CarSkipAction {
    NoOp,
    NextChannel,
    PrevChannel,
    SkipMessage,
    ReplayMessage,
}

/**
 * Pure, framework-free contextual skip decision for the Talkcan car
 * `MediaSession`. Returns the action pair `(onSkipToNext, onSkipToPrevious)`
 * for the given live PTT state so it is fully unit-testable without a running
 * `MediaSession` (design D4).
 *
 * The `Finalizing` rows in design D4 split into "with inbound audio queued or
 * playing" (Skip/Replay message) and "while idle" (channel advance/retreat).
 * The `CarMediaPttState` input alone cannot distinguish these two cases, so
 * `Finalizing` maps to `[SkipMessage][ReplayMessage]` which matches the spec
 * scenario "Next skips the current inbound message while Finalizing" / "Previous
 * replays the last heard message while Finalizing" — this is the case the
 * steering wheel user actually experiences during the on-the-road playback
 * window. The future inbound-backlog tracker (`pending unheard message
 * state` is not yet implemented) can disambiguate the two rows when it
 * ships, mapping idle Finalizing to channel-skip; the message-skip/replay
 * stubs on [PttForegroundService] currently no-op safely (see design D9).
 */
internal object CarSkipDecision {

    /**
     * @return `(onSkipToNextAction, onSkipToPreviousAction)` per design D4:
     *
     * | `CarMediaPttState` | `Next`         | `Prev`           |
     * |---|---|---|
     * | `NotReady`  | `NextChannel`  | `PrevChannel`   |
     * | `Ready`     | `NextChannel`  | `PrevChannel`   |
     * | `Recording` | `NoOp`         | `NoOp`           |
     * | `Finalizing`| `SkipMessage`  | `ReplayMessage`  |
     */
    fun fromState(state: CarMediaPttState): Pair<CarSkipAction, CarSkipAction> = when (state) {
        CarMediaPttState.NotReady -> CarSkipAction.NextChannel to CarSkipAction.PrevChannel
        CarMediaPttState.Ready -> CarSkipAction.NextChannel to CarSkipAction.PrevChannel
        CarMediaPttState.Recording -> CarSkipAction.NoOp to CarSkipAction.NoOp
        CarMediaPttState.Finalizing -> CarSkipAction.SkipMessage to CarSkipAction.ReplayMessage
    }
}