# capture-service

## Purpose

Unified PTT audio capture — single active `AudioRecord` owned by a service,
input source selected by active route, PCM emitted as a stream for channel
pipelines to subscribe to, plus `isCapturing` and live audio-level signals.

## Requirements

### Requirement: Single active capture session

The system SHALL own exactly one active PTT audio capture session at a time. A
second start attempt while a session is active SHALL be rejected by the capture
service rather than relying solely on dispatch-layer guards. The service's
`active` session reference SHALL transition to null synchronously when a
session finalizes (via `stop`, `cancelSession`, or max-duration), so a rapid
PTT re-press after finalization is accepted rather than rejected as
`SessionActive`.

#### Scenario: Second start while a session is active is rejected
- **WHEN** a capture session is active and a second start is requested
- **THEN** the system rejects the second start without creating a new `AudioRecord`
- **AND** the system leaves the existing capture session running unchanged

#### Scenario: No session active accepts a start
- **WHEN** no capture session is active and a start is requested
- **THEN** the system creates a single new capture session

#### Scenario: Rapid re-press after stop is accepted
- **WHEN** a capture session is finalized via `stop` and a new start is requested before any coroutine continuation runs
- **THEN** the system accepts the new start
- **AND** the system does not reject it as `SessionActive`

#### Scenario: Rapid re-press after cancelSession is accepted
- **WHEN** a capture session is finalized via `cancelSession` and a new start is requested before any coroutine continuation runs
- **THEN** the system accepts the new start
- **AND** the system does not reject it as `SessionActive`

### Requirement: Capture input source is selected by the active route

The system SHALL select the capture input source from the active audio route:
Bluetooth SCO when a SCO communication device is available, otherwise the phone
microphone. The capture service SHALL start its `AudioRecord` with the selected
source (`VOICE_COMMUNICATION` for SCO, `MIC` for phone) rather than holding one
recorder class per source.

#### Scenario: SCO route available selects VOICE_COMMUNICATION
- **WHEN** a SCO communication device is available and a capture session starts
- **THEN** the system records using the `VOICE_COMMUNICATION` audio source

#### Scenario: No SCO route selects phone microphone
- **WHEN** no SCO communication device is available and a capture session starts
- **THEN** the system records using the `MIC` audio source

### Requirement: Capture emits live PCM frames to subscribers

While a capture session is active, the system SHALL emit captured PCM chunks as a
live stream that channel pipelines and level consumers can subscribe to. The
stream SHALL NOT backpressure the audio read loop: a slow or absent subscriber
SHALL NOT stall capture.

#### Scenario: Subscriber receives chunks while capturing
- **WHEN** a capture session is active and a subscriber is collecting the frames stream
- **THEN** the subscriber receives PCM chunks as they are read from the microphone

#### Scenario: Slow subscriber does not stall the read loop
- **WHEN** a frames subscriber is slow and the capture session is active
- **THEN** the system drops stale chunks rather than blocking the audio read loop
- **AND** capture continues without underrun

#### Scenario: No subscriber does not prevent capture
- **WHEN** a capture session is active and there is no frames subscriber
- **THEN** capture proceeds normally and the terminal result is still available at session end

### Requirement: Capture returns terminal recorded PCM at session end

The system SHALL return the complete captured PCM (with sample rate) when a
capture session ends, so that consumers which operate on the whole capture (echo
playback, STT transcription) can consume it without subscribing to live frames.

#### Scenario: Session end returns the full capture
- **WHEN** a capture session that recorded audio is ended
- **THEN** the system returns a `RecordedPcm` containing all recorded samples and the sample rate

#### Scenario: Empty session returns empty PCM
- **WHEN** a capture session is ended without having recorded any audio
- **THEN** the system returns an empty `RecordedPcm` with the configured sample rate

### Requirement: Capture session exposes negotiated sample rate

The capture session SHALL expose the sample rate negotiated by the opened
capture source, so consumers that write capture-derived artifacts (WAV
headers, metadata) use the rate the source actually negotiated rather than a
hardcoded value.

#### Scenario: 16 kHz source exposes 16000
- **WHEN** the capture source negotiates a 16 kHz sample rate and a session starts
- **THEN** the session's `sampleRate` is 16000

#### Scenario: 8 kHz source exposes 8000
- **WHEN** the capture source negotiates an 8 kHz sample rate and a session starts
- **THEN** the session's `sampleRate` is 8000

### Requirement: Recording starts only after the ready beep completes

The production recorder MAY be opened and started before the ready beep for startup, silencing, and PCM-liveness preflight. The capture service SHALL keep that recorder behind an exclusive discard reader until beep completion. Channel-visible recording—live frames, VU updates, terminal PCM, and the running `CaptureSession`—SHALL start only after the ready beep completes, the discard reader has stopped and joined, and delivery is committed to the selected accepted channel target. The capture/audio input lifecycle SHALL release every route acquired by the session exactly once on every `startSession` outcome that does not hand off a committed running session; for SCO, the capture service SHALL release the route for `Cancelled`, `RecordingFailed`, or `RecordingSilenced` outcomes, preserving the 30-second warmup retention window. Channel controllers SHALL NOT release the same route again. When capture preflight fails before the ready beep contract is satisfied, the system SHALL provide problem feedback when possible.

#### Scenario: Channel-visible recording starts after beep completion
- **WHEN** the selected channel target has accepted input, recorder preflight succeeds, the ready beep finishes, and PTT is still held
- **THEN** the capture service SHALL stop and join the pre-commit discard reader
- **AND** create the committed running capture session with the opened source as its sole reader
- **AND** only post-beep samples SHALL reach live frames, VU updates, or terminal PCM for the committed channel target

#### Scenario: PTT released during the ready beep
- **WHEN** the ready beep is playing and the user releases PTT before the beep completes
- **THEN** the system SHALL discard and close the preflighted source without channel-visible audio
- **AND** cancel setup and release every acquired session route exactly once
- **AND** for SCO, the capture service release SHALL trigger the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the route

#### Scenario: PTT released during route acquisition or capture preflight
- **WHEN** route acquisition or capture preflight is in progress
- **AND** the user releases PTT before the ready beep completes
- **THEN** the system SHALL NOT open a recorder or play the ready beep when the applicable setup stage has not yet begun
- **AND** SHALL NOT deliver channel-visible user audio
- **AND** the audio input subsystem SHALL cancel the session and release acquired route resources exactly once
- **AND** when SCO acquisition is in progress, the capture service SHALL complete and release SCO, triggering the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the route

#### Scenario: Source preflight fails before ready beep
- **WHEN** the route is acquired, PTT remains held, and the capture source cannot be opened or proven usable before the ready beep
- **THEN** the system SHALL NOT play the ready beep or create a committed running capture session
- **AND** SHALL provide problem feedback when possible
- **AND** the capture/audio input lifecycle SHALL release the acquired session route exactly once
- **AND** for SCO, the capture service release SHALL trigger the 30-second warmup retention window
- **AND** the channel controller SHALL NOT additionally release the route

#### Scenario: Source fails after ready beep
- **WHEN** the ready beep completes and channel-visible capture has been committed
- **AND** the capture source subsequently fails
- **THEN** the system SHALL report failure or cancellation to the committed channel target
- **AND** SHALL release route resources exactly once


### Requirement: Maximum capture duration

The system SHALL cap a single capture session at 60 seconds. If PTT remains held
past the cap, the system SHALL end the session at the cap with the audio captured
so far.

#### Scenario: Capture ends at the 60-second cap
- **WHEN** a capture session reaches 60 seconds while PTT is still held
- **THEN** the system ends the session and returns the PCM captured up to the cap

### Requirement: Live capture level signal

While a capture session is active, the system SHALL expose a live audio level
signal (normalized RMS in the range 0..1) computed from the captured PCM in the
audio read loop. When no session is active, the level SHALL be 0.

#### Scenario: Level reflects microphone input while capturing
- **WHEN** a capture session is active and the microphone receives input
- **THEN** the system emits a level greater than 0 derived from the captured PCM

#### Scenario: Level is 0 when not capturing
- **WHEN** no capture session is active
- **THEN** the system exposes a level of 0

#### Scenario: Silent input yields a low level
- **WHEN** a capture session is active and the microphone receives silence
- **THEN** the system emits a level at or near 0

### Requirement: isCapturing transmit-state signal

The system SHALL expose a single `isCapturing` signal that is true while any
capture session is active and false otherwise. This signal SHALL be the unified
transmit-state indicator across all talk modes.

#### Scenario: Signal is true while capturing
- **WHEN** a capture session is active
- **THEN** the `isCapturing` signal is true

#### Scenario: Signal is false when idle
- **WHEN** no capture session is active
- **THEN** the `isCapturing` signal is false

### Requirement: Channel pipelines consume capture via the stream

Channel pipelines (echo, STT, STT↔TTS, journal, and future channels) SHALL obtain
captured audio from the capture service rather than owning their own recorder.
Output format is a property of the consuming pipeline, not of capture.

#### Scenario: In-memory consumer uses the terminal capture
- **WHEN** an in-memory consumer (echo or STT) handles a PTT cycle
- **THEN** it obtains the captured PCM from the capture session's terminal result
- **AND** it does not construct or own an `AudioRecord`

#### Scenario: Journal consumer writes WAV from the stream
- **WHEN** the journal pipeline handles a PTT cycle
- **THEN** it subscribes to the live frames stream and writes PCM incrementally to a WAV file
- **AND** it finalizes the WAV header at session end from the journal-side writer
- **AND** it does not construct a private recorder

### Requirement: Capture service is called by the audio input subsystem
The capture service SHALL remain the low-level owner of the active `AudioRecord`, live frames, terminal PCM, and ready-beep-before-user-speech sequencing. Higher-level PTT source, input-mode, route, channel commitment, and session route lifecycle orchestration SHALL be owned by the audio input subsystem. Capture startup outcomes SHALL give the audio input subsystem enough information to withhold ready beep, fail closed, and release the session route when committed user audio cannot be delivered.

#### Scenario: Subsystem starts capture preflight
- **WHEN** the audio input subsystem has resolved and validated the selected input route
- **AND** the selected channel target has accepted the input request
- **THEN** it calls the capture service to perform capture preflight, ready-beep sequencing, and recording setup
- **AND** the capture service returns either a committed running capture session or a typed setup failure

#### Scenario: Setup fails before handoff
- **WHEN** capture setup fails before a committed running capture session is handed to the audio input subsystem
- **THEN** the capture service closes any low-level recorder resources it opened
- **AND** the audio input subsystem marks the audio input session failed or cancelled
- **AND** the audio input subsystem releases the route associated with that session exactly once
- **AND** the selected channel does not release the route

#### Scenario: Running capture is handed off
- **WHEN** the capture service returns a committed running capture session
- **THEN** the audio input subsystem owns when to stop or cancel that session
- **AND** the audio input subsystem delivers post-beep live frames and terminal PCM to the committed channel target through the channel input contract

### Requirement: Capture startup exposes internal readiness evidence
The capture layer SHALL expose internal startup evidence needed by the audio input subsystem to decide whether capture may be reported as started. This evidence MAY include recorder open success, negotiated sample rate, active recording configuration, reported input device, and Android silencing status when those facts are available. The evidence SHALL remain internal to the audio input subsystem and SHALL NOT be exposed to channels.

#### Scenario: Android reports recorder silenced
- **WHEN** the capture source opens an `AudioRecord`
- **AND** Android reports the active recording configuration for that recorder is silenced
- **THEN** the audio input subsystem SHALL fail or cancel capture setup before delivering channel input start
- **AND** SHALL release the active session route exactly once

#### Scenario: Recording configuration is unavailable
- **WHEN** the capture source opens an `AudioRecord`
- **AND** Android does not provide enough recording configuration detail to identify the physical input device
- **THEN** the system SHALL use the required route facts that are available
- **AND** SHALL keep missing best-effort recording configuration facts as diagnostics rather than leaking them to channels

### Requirement: Capture cancellation during setup is route-safe
If the audio input session is cancelled while capture setup is suspended after route acquisition and before a running capture session is handed off, the system SHALL release the acquired route exactly once.

#### Scenario: Session cancelled during setup suspension
- **WHEN** an audio input session is cancelled while route acquisition, ready-beep playback, or source open is in progress
- **THEN** the system cancels setup
- **AND** releases any route acquired for that setup exactly once
- **AND** does not leave an active communication device or route lease behind

### Requirement: Channel-visible PCM begins after ready beep completion
When capture preflight starts a recorder before the ready beep to prove recorder readiness, the capture service SHALL discard all pre-commit PCM until ready-beep playback has completed. Neither live channel frames nor terminal `RecordedPcm` SHALL contain samples captured before ready-beep completion.

#### Scenario: Recorder is preflighted before ready beep
- **WHEN** the selected capture source must start recording before the ready beep to prove startup readiness
- **THEN** the capture service SHALL drain and discard pre-commit source data while the ready beep plays
- **AND** start channel-visible frame delivery only after the ready beep completes
- **AND** terminal recorded PCM SHALL contain only post-beep samples

#### Scenario: PTT is released during pre-commit drain
- **WHEN** PTT is released while recorder preflight or pre-commit draining is active
- **THEN** the capture service SHALL discard the opened source without delivering frames or terminal PCM
- **AND** return a cancelled setup outcome

### Requirement: Explicit recorder silencing rejects capture before commitment
The capture service SHALL reject an opened recorder before ready beep and channel handoff when Android explicitly reports the app's recording client as silenced. Recorder configuration that is unavailable or does not report silencing SHALL remain unknown rather than being treated as proof of silence or proof of readiness.

#### Scenario: Android reports client silenced
- **WHEN** an opened production recorder reports its client as silenced
- **THEN** the capture service SHALL close the recorder
- **AND** return `RecordingSilenced` before ready-beep playback
- **AND** the audio input subsystem SHALL not start the selected channel

#### Scenario: Android does not expose silencing evidence
- **WHEN** the opened recorder has no active recording configuration or no silencing value
- **THEN** the capture service SHALL retain that evidence as unknown
- **AND** SHALL NOT infer silencing from empty or quiet PCM

### Requirement: Production capture proves PCM liveness before readiness
An opened production capture source that opts into liveness proof SHALL produce at least one nonzero PCM sample during the configured pre-commit observation window before the ready beep and channel handoff. Zero-only or unavailable PCM throughout that window SHALL be treated as recorder-path failure, not as semantic user silence and not as Android client-silencing evidence.

#### Scenario: First recorder produces only digital zero
- **WHEN** the first production recorder produces no nonzero PCM during the pre-commit observation window
- **THEN** the capture service SHALL stop the discard reader and close that recorder
- **AND** retain the already acquired audio route
- **AND** open one replacement recorder after the configured retry delay
- **AND** require the replacement recorder to prove nonzero PCM before playing the ready beep

#### Scenario: Replacement recorder proves PCM liveness
- **WHEN** the replacement recorder produces nonzero PCM within its observation window
- **THEN** the capture service SHALL play the ready beep once
- **AND** transfer only the replacement recorder into the committed capture session
- **AND** SHALL NOT release and reacquire the audio route during recovery

#### Scenario: Every permitted recorder remains zero-only
- **WHEN** every permitted recorder attempt produces only zero PCM through its observation window
- **THEN** the capture service SHALL close every opened recorder
- **AND** return recording failure before the ready beep
- **AND** SHALL NOT expose a channel-visible capture session

#### Scenario: PTT is released during recorder retry
- **WHEN** PTT is released after a failed recorder attempt or during the retry delay
- **THEN** the capture service SHALL cancel before opening or committing another recorder
- **AND** close the current recorder and release setup ownership exactly once

#### Scenario: PTT is released during the final liveness window
- **WHEN** PTT is released while the final permitted recorder is still awaiting nonzero PCM
- **AND** that recorder's observation window expires
- **THEN** the capture service SHALL recheck cancellation before reporting exhausted recorder failure
- **AND** return a cancelled setup outcome without a ready beep or channel-visible session

### Requirement: Pre-commit and committed reads use distinct blocking contracts
Production capture sources SHALL use cancellable nonblocking reads only for the pre-commit discard and liveness phase. After channel handoff, the committed capture session SHALL use the source's normal blocking read so temporarily unavailable PCM does not become a rapid empty-read loop or suppress valid live audio.

#### Scenario: Capture crosses the ready-beep boundary
- **WHEN** a production recorder proves liveness and the ready beep completes
- **THEN** the pre-commit nonblocking discard reader SHALL stop and join
- **AND** the committed session SHALL become the recorder's sole reader
- **AND** committed live frames, VU updates, and terminal PCM SHALL use blocking reads