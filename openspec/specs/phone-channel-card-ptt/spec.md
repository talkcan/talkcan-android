## Purpose

TBD. Defines phone-originated PTT from functional channel cards and its interaction with channel routing, audio route selection, and PTT session timing.

## Requirements

### Requirement: Channel-card long-press starts PTT
The system SHALL allow the user to start a PTT session by long-pressing the main surface of any functional channel card. A phone-originated PTT session that remains held and unlocked SHALL end on finger release or cancellation. A phone-originated PTT session that has been slide-locked SHALL remain active after finger release and SHALL end only through an explicit stop action, maximum-duration cutoff, or system-forced termination such as app focus loss.

#### Scenario: Long-press starts PTT for selected channel
- **WHEN** the user long-presses the main surface of a functional channel card
- **THEN** the system SHALL set that channel as the active channel
- **AND** the system SHALL start a PTT session for that channel

#### Scenario: Unlocked long-press release ends PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the session has not been slide-locked
- **AND** the user releases or cancels the press
- **THEN** the system SHALL end the PTT session

#### Scenario: Locked long-press release does not end PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the session has been slide-locked
- **AND** the user releases the press
- **THEN** the system SHALL keep the PTT session active

### Requirement: Phone PTT supports inward slide-to-lock
The system SHALL allow a held phone-originated PTT session to become locked by sliding horizontally inward from the initial press side of the channel card. Locking SHALL prevent finger release from ending the PTT session.

#### Scenario: Left-side press locks by sliding right
- **WHEN** the user long-presses a functional channel card with the initial press on the left side of the card's PTT surface
- **AND** the phone-originated PTT session is active
- **AND** the user slides right past the lock threshold before releasing
- **THEN** the system SHALL lock the PTT session

#### Scenario: Right-side press locks by sliding left
- **WHEN** the user long-presses a functional channel card with the initial press on the right side of the card's PTT surface
- **AND** the phone-originated PTT session is active
- **AND** the user slides left past the lock threshold before releasing
- **THEN** the system SHALL lock the PTT session

#### Scenario: Incidental movement does not cancel active phone PTT
- **WHEN** a phone-originated PTT session is active from a channel-card long-press
- **AND** the user moves the finger without crossing the inward lock threshold
- **THEN** the system SHALL keep the PTT session active
- **AND** the system SHALL NOT treat the movement as a release

#### Scenario: Slide-lock before ready beep completes
- **WHEN** a phone-originated PTT session is armed and the ready beep has not completed
- **AND** the user slides inward past the lock threshold before releasing
- **THEN** the system SHALL remember the locked state
- **AND** recording SHALL start after the ready beep completes if the PTT session remains valid

#### Scenario: Explicit stop ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the user activates the visible stop affordance
- **THEN** the system SHALL end the PTT session

#### Scenario: Maximum duration ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the capture reaches the maximum capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by held phone-originated PTT sessions

#### Scenario: App focus loss ends locked phone PTT
- **WHEN** a phone-originated PTT session has been slide-locked
- **AND** the app loses foreground interaction or the gesture is system-cancelled
- **THEN** the system SHALL end the PTT session

### Requirement: PTT source is independent from audio route
The system SHALL resolve the audio route for each PTT session independently from whether the session was started by the RSM or by phone channel-card long-press.

#### Scenario: Phone PTT uses RSM audio when available
- **WHEN** the user starts PTT from a phone channel-card long-press
- **AND** the actual RSM audio route is usable
- **THEN** the system SHALL play beeps and record audio through the RSM route

#### Scenario: Phone PTT uses local audio when RSM audio is unavailable
- **WHEN** the user starts PTT from a phone channel-card long-press
- **AND** the actual RSM audio route is unavailable
- **THEN** the system SHALL play beeps through the phone loudspeaker local audio route
- **AND** record audio from the phone microphone

### Requirement: Phone-originated PTT preserves session timing
The system SHALL apply the same PTT session timing semantics to phone-originated PTT sessions as it applies to RSM-originated PTT sessions.

#### Scenario: Recording starts after ready beep
- **WHEN** a phone-originated PTT session starts on a ready channel
- **THEN** the system SHALL play the ready beep on the resolved audio route
- **AND** start recording only after the ready beep completes and the press is still held

#### Scenario: Release during ready beep cancels recording
- **WHEN** a phone-originated PTT session is playing the ready beep
- **AND** the user releases or cancels the press before the beep completes
- **THEN** the system SHALL NOT start recording

#### Scenario: Max duration applies to phone PTT
- **WHEN** a phone-originated PTT session remains held beyond the maximum capture duration
- **THEN** the system SHALL stop recording according to the same max-duration behavior used by RSM-originated PTT

### Requirement: Phone-originated PTT supports every functional channel
The system SHALL make phone channel-card PTT available for every functional channel that supports PTT behavior through the RSM path.

#### Scenario: Functional channel long-press is supported
- **WHEN** a functional channel card is visible on the main dashboard
- **THEN** the user SHALL be able to long-press that card to start a PTT session for that channel

### Requirement: Phone PTT is not terminated by unrelated device lifecycles
A held or slide-locked Phone PTT session SHALL remain active until a Phone-owned release or cancellation, maximum-duration cutoff, whole-service teardown, capture failure, or another existing global validity failure terminates it. RSM serial reconnect failure, RSM serial stream loss, explicit RSM serial disconnect, and stale Telecom lifecycle callbacks SHALL NOT terminate Phone PTT.

#### Scenario: Held Phone PTT survives RSM reconnect failure
- **WHEN** an unlocked Phone PTT session remains held
- **AND** an automatic RSM serial reconnect attempt fails
- **THEN** the Phone PTT session SHALL remain active
- **AND** capture SHALL continue without a synthetic Phone release

#### Scenario: Locked Phone PTT survives RSM reconnect failure
- **WHEN** a Phone PTT session is slide-locked
- **AND** an automatic RSM serial reconnect attempt fails or its serial stream ends
- **THEN** the Phone PTT session SHALL remain locked and active
- **AND** the visible stop action SHALL remain its ordinary explicit terminal control

#### Scenario: Phone PTT survives stale Telecom timeout
- **WHEN** Phone PTT is pending or active
- **AND** a timeout or terminal callback arrives from an earlier Telecom operation
- **THEN** the Phone session SHALL remain unchanged

#### Scenario: Existing Phone terminal controls remain effective
- **WHEN** Phone PTT receives finger release while unlocked, explicit stop while locked, focus loss, gesture cancellation, maximum-duration cutoff, capture failure, or whole-service teardown
- **THEN** the session SHALL terminate according to its existing Phone or global lifecycle contract
