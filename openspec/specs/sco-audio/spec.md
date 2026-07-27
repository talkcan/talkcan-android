## Purpose

Defines the Bluetooth SCO (Synchronous Connection Oriented) audio route lifecycle — acquisition from inactive state, cold-start priming for reliable first-playback audio, device-level AudioTrack routing, and the warmup retention window that keeps the SCO path active between PTT sessions.

## Requirements

### Requirement: SCO route is acquired on PTT press
The system SHALL acquire the Work Bluetooth SCO communication route by targeting the `B02PTT-FF01` `BluetoothDevice` through the `BluetoothHeadset` profile, then using the active `TYPE_BLUETOOTH_SCO` `AudioDeviceInfo` only as the transport after target RSM ownership is proven.

#### Scenario: Work SCO is inactive on PTT press
- **WHEN** Work SCO is inactive, Work mode is selected or auto-selected, and the user presses RSM PTT
- **THEN** the system SHALL resolve the target RSM `BluetoothDevice`
- **AND** verify the target RSM Headset/HFP profile connection is connected
- **AND** call `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** poll `BluetoothHeadset.isAudioConnected(targetRsm)` or observe target audio state until RSM HFP audio is connected
- **AND** select the active `TYPE_BLUETOOTH_SCO` communication device as the Work transport
- **AND** report SCO state transitions: `Inactive → Starting → Active`

#### Scenario: Work SCO is already active on PTT press
- **WHEN** Work SCO is already active and owned by `B02PTT-FF01` from a previous warm Work session
- **AND** the user presses RSM PTT
- **THEN** the system SHALL return immediately without re-acquisition
- **AND** the system SHALL report SCO state `Active`

#### Scenario: Target RSM HFP is not connected
- **WHEN** Work route acquisition starts
- **AND** the target RSM Headset/HFP profile is not connected
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP not connected")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted voice recognition start fails
- **WHEN** Work route acquisition calls `BluetoothHeadset.startVoiceRecognition(targetRsm)`
- **AND** the call returns false or throws
- **THEN** the system SHALL report SCO state `Failed("Target RSM HFP audio start failed")`
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Targeted HFP audio connection times out
- **WHEN** Work route acquisition starts target RSM voice recognition
- **AND** `BluetoothHeadset.isAudioConnected(targetRsm)` does not become true before timeout
- **THEN** the system SHALL report SCO state `Failed("Timed out waiting for target RSM HFP audio")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: Generic SCO device is not found after target ownership proof
- **WHEN** `BluetoothHeadset.isAudioConnected(targetRsm)` is true
- **BUT** no `TYPE_BLUETOOTH_SCO` communication transport is available to route AudioTrack output
- **THEN** the system SHALL report SCO state `Failed("Bluetooth SCO transport not available")`
- **AND** the system SHALL stop target RSM voice recognition if needed
- **AND** the system SHALL return acquisition failure

#### Scenario: SCO acquisition times out
- **WHEN** the system polls for 5 seconds and the SCO route does not become active
- **THEN** the system reports SCO state `Failed("Timed out waiting for SCO route")`
- **AND** the system returns acquisition failure to the caller
- **AND** the caller SHALL NOT proceed with beep or recording

#### Scenario: SCO device is not found
- **WHEN** no Bluetooth SCO device is available during acquisition
- **THEN** the system reports SCO state `Failed("Bluetooth SCO headset not available")`
- **AND** the system returns acquisition failure

### Requirement: Ready beep follows SCO and recorder preflight with reliable audible output

The system SHALL play the ready beep only after the SCO route is active and the opened recorder has passed required startup, silencing, and PCM-liveness preflight. The beep output SHALL be audibly routed through the SCO headset and SHALL mark the boundary after which captured PCM may become channel-visible.

#### Scenario: Cold-start SCO ready beep after recorder preflight
- **WHEN** SCO was inactive, the system acquires SCO successfully, and recorder preflight succeeds while PTT remains held
- **THEN** the system SHALL route the AudioTrack explicitly to the SCO communication device via `setPreferredDevice`
- **AND** send a priming PCM buffer through the SCO route before the beep waveform to establish the link-layer SCO packet stream
- **AND** play the ready beep after recorder preflight without exposing pre-beep capture PCM
- **AND** the user SHALL hear the beep through the Bluetooth headset

#### Scenario: Warm-start SCO ready beep after recorder preflight
- **WHEN** SCO remains active from a previous warm session and recorder preflight succeeds while PTT remains held
- **THEN** the system SHALL play the ready beep through the SCO device route without cold-start output priming
- **AND** SHALL NOT expose pre-beep capture PCM

### Requirement: Recording always starts after ready beep completes
The production recorder MAY already be running behind the pre-commit discard boundary for readiness proof, but channel-visible recording SHALL start only after ready-beep playback has fully completed and the discard reader has stopped and joined.

#### Scenario: Beep completes before channel-visible recording starts
- **WHEN** the ready beep finishes playing and PTT is still held
- **THEN** the system SHALL hand the opened Bluetooth SCO microphone source to the committed capture session
- **AND** only post-beep PCM SHALL reach live frames, VU state, or terminal recording
- **AND** the system SHALL report status transition: `Beeping → Recording`

#### Scenario: PTT is released during beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL NOT create a channel-visible recording session
- **AND** SHALL discard and close the preflighted recorder
- **AND** cancel setup
- **AND** retain the warm SCO route through the capture service's release contract

#### Scenario: PTT is released during SCO acquisition (short tap)
- **WHEN** SCO is being acquired and the user releases PTT before SCO becomes active
- **THEN** the system SHALL continue SCO acquisition to completion
- **AND** SHALL NOT open a recorder or play the ready beep
- **AND** SHALL retain the SCO route warm for 30 seconds after capture-service cleanup

### Requirement: AudioTrack routes through SCO device

The system SHALL explicitly route all `PcmOutput` AudioTrack instances through the active SCO communication device to guarantee the audio reaches the Bluetooth headset.

#### Scenario: AudioTrack is created with SCO device preference
- **WHEN** the system creates an AudioTrack for beep playback or audio playback
- **THEN** the system SHALL set the AudioTrack's preferred device to the currently active SCO communication device
- **AND** the system SHALL fall back to the default routing if no SCO device is active (Audio track's default behavior)

### Requirement: Audio output owns route release

The `PcmOutput` implementation paired with a resolved audio route SHALL retain the route-specific release behavior exposed by `releaseRoute()`. The central audio input session owner SHALL invoke `releaseRoute()` for the active session when capture, post-capture processing, failure, or cancellation reaches a terminal state. Channel controllers SHALL NOT receive the resolved route and SHALL NOT call `releaseRoute()` or `ScoRoute.release()` directly in the PTT flow. The release mode (warm 30-second retention for Work SCO, immediate release for Telecom, no-op for local fallback) SHALL remain a property of the resolved route output, not a per-channel decision.

#### Scenario: SCO output releases with warm retention
- **WHEN** a PTT cycle completes on the SCO route and the audio input session owner releases the active route
- **THEN** the SCO route's `release()` is invoked
- **AND** the SCO controller starts the 30-second warmup retention window

#### Scenario: Telecom output releases immediately
- **WHEN** a PTT cycle completes on the telecom route and the audio input session owner releases the active route
- **THEN** the SCO route is released immediately (no warmup window)
- **AND** the system awaits telecom disconnect before any post-capture playback

#### Scenario: Local fallback output release is a no-op
- **WHEN** a PTT cycle completes on the local fallback route and the audio input session owner releases the active route
- **THEN** no route resources are released (there are none)

#### Scenario: Channel controller does not touch route release
- **WHEN** a channel controller handles PTT input, terminal audio, cancellation, or max-duration on any route
- **THEN** the controller SHALL NOT call `ScoRoute.release()` or `PcmOutput.releaseRoute()`
- **AND** the audio input session owner SHALL release the active route exactly once

### Requirement: Work route cleanup is session-owned
The Work SCO route SHALL be cleared only by the audio input session that owns it or by explicit fail-safe service teardown. A stale release from an older session SHALL NOT clear a newer session's communication device.

#### Scenario: Stale Work release ignored
- **WHEN** an older Work session release runs after a newer audio input session has become active
- **THEN** the older release does not clear the newer session's communication device
- **AND** the newer session remains responsible for its own route release

### Requirement: Work route switches away require observed release
When switching from a warm or active Work/RSM SCO route to any non-Work input mode, the system SHALL require observed release of the target RSM audio path before the next route may proceed as ready. A timeout while waiting for release SHALL fail closed and SHALL NOT be treated as successful release.

#### Scenario: Warm Work route reused by Work
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `Work`
- **THEN** the system SHALL reuse the warm Work route when the route is still owned by the target RSM
- **AND** SHALL NOT force a release/reacquire cycle solely because the route is warm

#### Scenario: Warm Work route blocks On-the-road until released
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `OnTheRoad`
- **THEN** the system SHALL request immediate release of the target RSM audio path
- **AND** SHALL wait until Android reports the target RSM HFP audio is disconnected and the communication device is not the selected RSM SCO route
- **AND** SHALL NOT proceed to On-the-road capture setup until those facts are true

#### Scenario: Work release timeout fails closed
- **WHEN** the system is switching away from a warm or active Work/RSM route
- **AND** Android does not report the target RSM audio path released before the configured timeout
- **THEN** the system SHALL fail or cancel the next route setup
- **AND** SHALL NOT infer release from elapsed time
- **AND** SHALL leave no newly-started non-Work capture session behind

#### Scenario: Warm Work route does not block phone after observed release
- **WHEN** the Work/RSM SCO route is warm from a previous Work session
- **AND** the next PTT source selects `OnAPinch`
- **THEN** the system SHALL ensure the Work route is no longer the active communication route before local microphone capture is reported as started
- **AND** the phone channel input flow SHALL remain route-object-free

### Requirement: SCO release on capture setup failure
The capture service SHALL release the SCO route itself when it acquires the SCO route during `startSession` and setup fails before a running session is handed off (cancelled because PTT was released during acquisition or beep, or the capture source could not be opened). Channel controllers SHALL NOT release the SCO route on those failure outcomes.

#### Scenario: Capture cancelled during beep — service releases SCO
- **WHEN** the capture service acquires SCO, begins the ready beep, and PTT is released before the beep completes
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

#### Scenario: Capture source open fails — service releases SCO
- **WHEN** the capture service acquires SCO, plays the ready beep, and the capture source cannot be opened
- **THEN** the capture service releases the SCO route
- **AND** the channel controller does not additionally release the SCO route

### Requirement: SCO release on post-capture consumer cancellation
The audio input session owner SHALL release the audio route via `route.output.releaseRoute()` on every post-capture consumer exit path including normal completion, failure, and cancellation. Channel controllers SHALL NOT release the route directly.

#### Scenario: Transcription cancelled by new PTT press releases the route
- **WHEN** a STT or STT↔TTS transcription job is in flight and the user presses PTT again
- **THEN** the transcription job is cancelled
- **AND** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced (no leak)

#### Scenario: Transcription completes normally and releases the route
- **WHEN** a STT or STT↔TTS transcription job completes successfully
- **THEN** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced

#### Scenario: Transcription fails and releases the route
- **WHEN** a STT or STT↔TTS transcription job fails with a non-cancellation error
- **THEN** the audio input session owner releases the audio route via `route.output.releaseRoute()`
- **AND** the SCO reference count is balanced

### Requirement: Non-Work problem feedback honors route-release gates
When a PTT request selects a non-Work route but cannot start capture because the selected channel is unavailable or refuses input, the system SHALL await the resolved route's route gate before playing problem feedback. It SHALL NOT play non-Work feedback while a prior Work/RSM route is still observed as active.

#### Scenario: Warm Work route precedes unavailable phone channel
- **WHEN** a warm Work/RSM route exists
- **AND** phone PTT selects On-a-pinch
- **AND** the selected channel cannot accept input
- **THEN** the system SHALL await observed Work-route release before attempting local problem feedback
- **AND** SHALL not retain the RSM communication device solely because capture was refused

#### Scenario: Work-route release gate fails before feedback
- **WHEN** a non-Work problem-feedback attempt cannot observe Work-route release before its configured timeout
- **THEN** the system SHALL not play feedback through the stale route
- **AND** SHALL perform the resolved route cleanup contract without leaving a new route lease behind

### Requirement: Work playback owns an explicit target-RSM SCO lease
A Work-mode channel-content playback operation SHALL acquire its own target-RSM-owned HFP/SCO route lease after host playback admission and before writing PCM. An existing warm SCO transport MAY make acquisition immediate, but warmth alone SHALL NOT authorize playback or substitute for an operation lease.

#### Scenario: Work route remains warm after capture
- **WHEN** Work capture has released its client lease but the target RSM SCO transport remains warm
- **AND** channel-content playback is admitted in Work mode
- **THEN** playback SHALL acquire a new Work route client lease before writing PCM
- **AND** it SHALL release that lease exactly once after playback cleanup

#### Scenario: Work playback is requested while capture owns SCO
- **WHEN** Work capture still owns its route lease
- **AND** a response is pending for Work playback
- **THEN** host half-duplex admission SHALL prevent playback route acquisition
- **AND** the response SHALL remain pending rather than sharing the capture lease

#### Scenario: Work playback cannot prove target ownership
- **WHEN** Work playback admission begins but target RSM HFP ownership or SCO transport cannot be proven
- **THEN** playback route acquisition SHALL fail closed
- **AND** no PCM or rejection feedback SHALL be played through car, phone, or an anonymous unproven endpoint
- **AND** the response SHALL remain pending and unheard

### Requirement: Active Work playback supplies rejection feedback through its owned stream
While Work response playback owns the target-RSM route, a PTT rejection beep SHALL be mixed by the active playback operation through that same route and SHALL NOT acquire another SCO client or modify capture route ownership.

#### Scenario: RSM PTT is rejected during Work playback
- **WHEN** the target RSM is playing a response under an owned Work playback lease
- **AND** PTT is pressed
- **THEN** the active stream SHALL duck speech and overlay the rejection beep
- **AND** SCO client ownership SHALL remain with the same playback operation until normal completion or explicit skip
