## Purpose

Defines how PTT input events and asynchronous response playbacks are routed and scheduled based on channel instance readiness, active input modes, audio contention, and explicit RSM skip/reselection commands.

## Requirements

### Requirement: Channel readiness state
The system SHALL obtain readiness from the runtime entry associated with each channel instance. Readiness SHALL combine valid enabled provider configuration with the provider/runtime-declared availability of semantic capabilities required by that instance. Readiness and preparation eligibility SHALL be instance-specific even when multiple instances reference the same provider. Core routing SHALL NOT inspect provider identity, a built-in channel kind, or platform transport state to derive readiness.

#### Scenario: Ready instance
- **WHEN** an enabled channel instance has provider-validated configuration and its runtime reports every required semantic capability available
- **THEN** its runtime readiness SHALL be true

#### Scenario: Instance dependency unavailable
- **WHEN** a channel instance lacks valid configuration or its runtime reports a required semantic capability unavailable
- **THEN** its runtime readiness SHALL be false
- **AND** readiness of other instances SHALL be evaluated independently

#### Scenario: Inactive instance retains dependency readiness
- **WHEN** an enabled inactive instance has valid configuration and its runtime reports all required semantic capabilities available
- **THEN** its runtime readiness SHALL remain true
- **AND** active selection or shared-controller activation state SHALL NOT be treated as dependency availability

#### Scenario: Provider is unavailable
- **WHEN** an instance's referenced provider is missing, incompatible, or failed to initialize
- **THEN** its runtime entry SHALL report unavailable rather than ready
- **AND** core routing SHALL preserve and identify the instance without attempting provider-specific readiness logic

### Requirement: PTT routing respects readiness
The system SHALL evaluate the active catalogue instance's runtime readiness and preparation availability before dispatching a PTT capture, regardless of which input mode or actuator initiated PTT. Route resolution SHALL remain based on active `InputMode`, not `PttSource`. Ready or recoverable input SHALL be prepared through the ID-keyed runtime registry. A not-ready runtime whose provider/runtime reports recoverable preparation available SHALL enter pending input preparation; every other not-ready or unavailable runtime SHALL follow the immediate problem-feedback path. Core routing SHALL NOT use Keyboard, provider, implementation, hardware, or connection-state checks to choose recovery.

#### Scenario: PTT on a ready active instance in Work mode
- **WHEN** PTT is pressed while `Work` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Work audio route
- **AND** request a committed input target from the runtime associated with the active instance ID

#### Scenario: PTT on a ready active instance in On-the-road mode
- **WHEN** PTT is pressed while `OnTheRoad` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the Telecom audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: PTT on a ready active instance in On-a-pinch mode
- **WHEN** PTT is pressed while `OnAPinch` is active and the selected runtime is ready
- **THEN** the system SHALL resolve the local audio route
- **AND** request a committed input target from the same ID-keyed runtime registry

#### Scenario: Not-ready runtime enters recoverable preparation
- **WHEN** PTT is pressed while the selected runtime is not ready
- **AND** that runtime reports recoverable input preparation available
- **THEN** the system SHALL reserve one pending audio input session
- **AND** request recoverable input preparation from that runtime
- **AND** it SHALL NOT select the immediate not-ready problem-feedback path solely because a required capability is currently unavailable

#### Scenario: Not-ready runtime does not offer recovery
- **WHEN** PTT is pressed while the selected runtime is not ready or unavailable
- **AND** that runtime does not report recoverable input preparation available
- **THEN** the system SHALL select the immediate problem-feedback path
- **AND** it SHALL NOT request channel input preparation

#### Scenario: Phone PTT selects an instance before readiness admission
- **WHEN** the user starts phone PTT from any persisted functional channel card
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that card's stable instance ID as active regardless of preparation state
- **AND** route PTT admission through the matching runtime entry
- **AND** a non-recoverable unavailable runtime SHALL produce host-owned problem feedback without capture

#### Scenario: RSM PTT auto-transitions to Work mode
- **WHEN** RSM PTT is pressed while Work mode is available
- **THEN** the system SHALL transition to `Work`
- **AND** route preparation to the active runtime instance

#### Scenario: Android Auto PTT auto-transitions to On-the-road mode
- **WHEN** Android Auto PTT is initiated while On-the-road mode is available
- **THEN** the system SHALL transition to `OnTheRoad`
- **AND** route preparation to the active runtime instance

#### Scenario: Runtime refuses preparation
- **WHEN** the active runtime becomes unavailable, recoverable preparation fails, or the runtime refuses input after route gating but before capture starts
- **THEN** the system SHALL NOT start capture or play the ready beep
- **AND** host-owned error feedback and route cleanup SHALL follow the existing audio session policy

### Requirement: Two-tone error beep on not-ready PTT
The system SHALL emit a characteristic two-tone error beep over the host-resolved audio route if PTT is pressed while the target runtime is not ready and does not offer recoverable input preparation. If provider/runtime-driven recoverable preparation is attempted, the system SHALL defer the error decision until preparation fails or times out. The audio route SHALL be resolved from the active `InputMode`, and the host SHALL remain responsible for beep playback and route cleanup.

#### Scenario: PTT on a non-recoverable not-ready active channel in Work mode
- **WHEN** PTT is pressed while in `Work` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the Work mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: PTT on a non-recoverable not-ready active channel in On-the-road mode
- **WHEN** PTT is pressed while in `OnTheRoad` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the On-the-road mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: PTT on a non-recoverable not-ready active channel in On-a-pinch mode
- **WHEN** PTT is pressed while in `OnAPinch` mode and the active runtime is not ready and does not offer recovery
- **THEN** the system SHALL play a two-tone error beep on the On-a-pinch mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: Phone PTT on a non-recoverable not-ready channel card
- **WHEN** a functional channel card is long-pressed and that runtime is not ready and does not offer recovery
- **THEN** the system SHALL transition to `OnAPinch`
- **AND** set that channel's stable instance ID as active
- **AND** play a two-tone error beep on the On-a-pinch mode audio route when possible
- **AND** drop the PTT capture without requesting a committed channel target

#### Scenario: Recoverable preparation succeeds
- **WHEN** PTT is pressed for a not-ready runtime that offers recoverable preparation
- **AND** preparation makes the required semantic capabilities available before timeout
- **THEN** the system SHALL NOT play the two-tone error beep
- **AND** it SHALL continue through the normal ready beep and host-owned capture path

#### Scenario: Recoverable preparation fails
- **WHEN** PTT is pressed for a not-ready runtime that offers recoverable preparation
- **AND** preparation fails, is cancelled, or times out
- **THEN** the system SHALL play the two-tone error beep on the resolved input-mode route when possible
- **AND** it SHALL drop the PTT capture without playing the ready beep


### Requirement: Selection-aware asynchronous response playback
The host-owned channel router SHALL admit a synthesized assistant response for playback independently of the PTT session that produced its user turn. Admission SHALL require that the response's channel instance is the current active channel and that the audio subsystem accepts a playback operation. A response that is not admitted SHALL remain pending and unheard in the durable channel message projection rather than being discarded or played on an unrelated channel.

#### Scenario: Response arrives for the selected channel
- **WHEN** a synthesized assistant response arrives for the current active channel
- **AND** the audio subsystem admits a playback operation
- **THEN** the router SHALL schedule playback immediately without opening a new PTT input session
- **AND** the response SHALL remain associated with its channel instance

#### Scenario: Response arrives for an unselected channel
- **WHEN** a synthesized assistant response arrives for a channel instance that is not active
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL NOT start playback for that response
- **AND** it SHALL NOT change the active channel

#### Scenario: Selected-channel playback is blocked
- **WHEN** a synthesized assistant response arrives for the active channel
- **AND** the audio subsystem cannot admit playback because another host-owned audio operation has contention
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL enqueue the response for a later admission attempt

#### Scenario: User returns to a channel with pending responses
- **WHEN** the user selects a channel instance with one or more pending unheard responses
- **AND** the audio subsystem admits playback
- **THEN** the router SHALL schedule that channel's pending responses in arrival order
- **AND** each response SHALL remain unheard until its playback completes or the user explicitly skips it

#### Scenario: Channel changes before queued admission
- **WHEN** a response is waiting for playback admission
- **AND** the active channel changes before playback starts
- **THEN** the router SHALL cancel that response's pending playback admission
- **AND** retain the response as pending and unheard for the next selection of its channel
#### Scenario: Playback is interrupted before completion
- **WHEN** an admitted response playback is interrupted or cancelled before completion
- **AND** the user has not explicitly skipped that response
- **THEN** the router SHALL retain the response as pending and unheard
- **AND** it SHALL NOT commit the response as heard
- **AND** a later playback attempt SHALL use the durable response order when its channel is selected and audio is admitted

#### Scenario: Heard response is not replayed
- **WHEN** a response playback completes or the user explicitly skips that response
- **THEN** the router SHALL commit the response as heard
- **AND** it SHALL NOT enqueue that same response for automatic replay

### Requirement: Asynchronous response playback is serialized under audio contention
The host-owned playback scheduler SHALL serialize assistant response playback with other host-owned output operations. It SHALL NOT overlap two response playbacks, interrupt an admitted response solely because another channel becomes active, or drop a response because output is temporarily occupied. Each channel's response order SHALL be FIFO by durable response arrival order.

#### Scenario: Output is occupied by another operation
- **WHEN** a selected channel response is ready for playback
- **AND** another host-owned output operation currently owns the audio admission
- **THEN** the scheduler SHALL leave the response queued
- **AND** it SHALL attempt admission after the occupying operation releases output
- **AND** it SHALL preserve the response's channel and arrival order

#### Scenario: Multiple responses arrive for one channel
- **WHEN** multiple synthesized responses arrive for the same channel before playback can complete
- **THEN** the scheduler SHALL queue them in durable arrival order
- **AND** it SHALL play at most one response at a time
- **AND** it SHALL mark no response heard before that response's playback completes or is explicitly skipped

#### Scenario: Responses for different channels contend
- **WHEN** pending responses exist for more than one channel instance
- **THEN** the scheduler SHALL consider only the current active channel for new playback admission
- **AND** responses belonging to inactive channels SHALL remain pending and unheard
- **AND** selecting another channel SHALL make that channel's pending queue eligible without reordering responses within it

### Requirement: Channel routing publishes asynchronous response projections
The host-owned routing projection SHALL identify the active channel instance, per-channel queued user-turn count, processing state, pending unheard response count, and playback state without exposing SDK request/response objects, Android UI objects, audio-route objects, or transport objects. Processing state SHALL distinguish at least queued work, active work, waiting for a client tool, synthesis, pending response, completed idle state, and error state sufficiently for surfaces to render host-owned status.

#### Scenario: User turn or run is processing
- **WHEN** a released user turn is queued or its asynchronous run is transcribing, running, waiting for a tool, or synthesizing
- **THEN** the channel projection SHALL identify that channel as processing
- **AND** the projection SHALL retain the channel's stable instance ID
- **AND** no surface SHALL need to inspect a runtime, SDK, or transport object to render the status
#### Scenario: Queued user turns are visible
- **WHEN** one or more released user turns are waiting to begin asynchronous processing
- **THEN** the channel projection SHALL expose their queued count
- **AND** the count SHALL decrease only when each queued turn enters processing or a host-owned terminal state
- **AND** no surface SHALL need to inspect a runtime, SDK, or transport object to render the status

#### Scenario: Response becomes pending or heard
- **WHEN** a synthesized assistant response cannot be played immediately
- **THEN** the projection SHALL increment that channel's pending unheard response count
- **WHEN** playback completes or the user explicitly skips the response
- **THEN** the projection SHALL mark that response heard
- **AND** the pending unheard response count SHALL decrease accordingly

#### Scenario: Projection changes
- **WHEN** active selection, processing state, pending count, or playback state changes
- **THEN** the router SHALL publish a new host-owned projection
- **AND** phone and Android Auto consumers SHALL be able to refresh without accessing channel implementation internals

### Requirement: Capture contention retains asynchronous responses pending
The host channel router SHALL treat every reserved, acquiring, recording, finalizing, or releasing capture operation as unavailable output admission. Selected-channel responses that become ready during that interval SHALL accumulate durably in channel FIFO order and SHALL remain pending and unheard until capture cleanup completes and the host re-admits playback.

#### Scenario: Selected response arrives while recording
- **WHEN** a response arrives for the active channel while any PTT input operation owns host audio
- **THEN** the router SHALL retain the response pending and unheard
- **AND** it SHALL retry admission after the existing capture terminal-completion notification

#### Scenario: Responses arrive for several channels during recording
- **WHEN** multiple channels gain pending responses while capture owns host audio
- **THEN** the router SHALL preserve each channel's durable response order
- **AND** after capture it SHALL consider only the then-active channel for playback admission

### Requirement: Explicit SOS skip pauses automatic playback for that channel
When host playback control reports that RSM SOS explicitly skipped the active response, the router SHALL mark only that response heard, preserve later responses pending, and set a paused-drain state for that channel. Scheduler wakeups other than deliberate same-channel reselection SHALL NOT clear the pause.

#### Scenario: SOS skips the FIFO head
- **WHEN** response A is playing and responses B and C are pending for the same channel
- **AND** RSM SOS skips A
- **THEN** A SHALL transition to heard by explicit skip
- **AND** B and C SHALL remain pending in order
- **AND** B SHALL NOT start automatically after A's route cleanup

#### Scenario: Audio availability changes while paused
- **WHEN** a paused channel's selected-mode endpoint disconnects and later reconnects
- **THEN** the router SHALL leave the queue paused
- **AND** route recovery SHALL NOT itself admit playback

### Requirement: Shared channel reselection resumes a paused queue
The shared active-channel selection operation SHALL distinguish deliberate reselection from passive projection refresh. Deliberately selecting the already-active paused channel through phone, RSM, or car controls SHALL clear only that channel's paused-drain state and request FIFO playback admission.

#### Scenario: Phone reselects the active channel
- **WHEN** the phone surface deliberately selects the already-active channel whose queue is paused
- **THEN** the router SHALL resume that channel's queue
- **AND** pending responses SHALL become eligible without changing channel order

#### Scenario: RSM or car reselects the active channel
- **WHEN** an RSM or car control deliberately selects the already-active paused channel
- **THEN** the same shared reselection behavior SHALL resume the queue
- **AND** no control-surface-specific playback path SHALL be used

#### Scenario: Catalogue projection repeats active identity
- **WHEN** a passive state refresh republishes the same active channel identity
- **THEN** the router SHALL NOT interpret it as deliberate reselection
- **AND** it SHALL leave paused-drain state unchanged
