## Purpose

Defines how the Talkcan car `MediaSession` interprets steering-wheel `onSkipToNext` and `onSkipToPrevious` callback events as contextual actions — channel switching while idle / Ready, inbound-message skip or replay while Finalizing, and no-op while Recording or NotReady. The decision is encapsulated in a pure state predicate so it is unit-testable without a running `MediaSession` and deterministic across OEM head units.

## Requirements

### Requirement: Steering-wheel Next and Previous are contextual on Talkcan MediaSession
The system SHALL interpret `MediaSession.Callback` `onSkipToNext` and `onSkipToPrevious` on the Talkcan car media session as channel-switching while idle and as inbound-message skip or replay while audio is active, governed by the current `CarMediaPttState`.

#### Scenario: Next switches active channel while Ready
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is Ready (idle)
- **AND** the user invokes Next on the steering wheel
- **THEN** the system SHALL set the next channel in the stable channel ordering as the single active Talkcan channel
- **AND** the now-playing card SHALL reflect the newly active channel

#### Scenario: Previous switches active channel while Ready
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is Ready (idle)
- **AND** the user invokes Previous on the steering wheel
- **THEN** the system SHALL set the previous channel in the stable channel ordering as the single active Talkcan channel
- **AND** Previous SHALL saturate at the first channel rather than wrap

#### Scenario: Next skips the current inbound message while Finalizing
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is Finalizing with inbound audio playing or queued
- **AND** the user invokes Next on the steering wheel
- **THEN** the system SHALL skip the currently playing inbound message
- **AND** the skipped message SHALL be marked as heard for autoplay purposes
- **AND** the system SHALL advance to the next queued inbound message if one exists, otherwise return to Ready

#### Scenario: Previous replays the last heard message while Finalizing
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is Finalizing with audio playing or with a prior heard message available
- **AND** the user invokes Previous on the steering wheel
- **THEN** the system SHALL replay the last heard message on the active channel

#### Scenario: Next and Previous are no-ops during Recording
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is Recording
- **AND** the user invokes Next or Previous on the steering wheel
- **THEN** the system SHALL take no action
- **AND** the active channel SHALL NOT change
- **AND** the capture session SHALL NOT be interrupted

#### Scenario: Next and Previous are no-ops while NotReady
- **WHEN** the Talkcan car media session is active
- **AND** the live PTT state is NotReady
- **AND** the user invokes Next or Previous on the steering wheel
- **THEN** the system SHALL take no action

#### Scenario: Skip action decided by a pure state predicate
- **WHEN** the system implements the contextual skip decision
- **THEN** the decision SHALL be encapsulated in a pure function from `CarMediaPttState` to a `CarSkipAction` enum
- **AND** the function SHALL be unit-testable without a running MediaSession
- **AND** the empty-channel-list case SHALL produce a no-op for both Next and Previous