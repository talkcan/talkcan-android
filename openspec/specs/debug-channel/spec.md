## Purpose

Defines the Debug Channel as an external Lua package providing echo, delayed-echo, STT, TTS, and STT_TTS diagnostic modes through host-routed deferred playback.

## Requirements

### Requirement: Debug synthesized modes use host-routed deferred playback
The Debug Channel package SHALL request playback only through `talkcan.playback.schedule` using opaque captured or synthesized audio. A queue entry SHALL retain the authorizing channel instance and runtime generation, not a PTT-session route or platform output. At physical playback admission, the host SHALL re-evaluate current channel selection, half-duplex eligibility, `InputMode`, and `ModePlaybackRouteResolver`, and SHALL route through the resulting current output. The Debug runtime SHALL NOT select, query, retain, or invoke an Android audio output or route. If no valid authorized output can be admitted, the host SHALL keep the entry pending while its generation remains current or terminate it with the applicable typed failure; it SHALL NOT fall back to an ambient local output.

#### Scenario: TTS queues synthesized audio
- **WHEN** a committed Debug instance in `TTS` mode synthesizes terminal input and successfully schedules the returned audio
- **THEN** queue admission SHALL complete exactly once and `handle_input` SHALL return its successful terminal result
- **AND** if the queued entry later receives successful route admission and playback completion, physical playback SHALL occur exactly once through the then-current resolved output

#### Scenario: STT to TTS queues synthesized transcript
- **WHEN** a committed Debug instance in `STT_TTS` mode successfully transcribes captured audio, synthesizes the transcript, and schedules the result
- **THEN** those semantic operations SHALL execute in order and queue admission SHALL complete exactly once
- **AND** if the queued entry later receives successful route admission and playback completion, physical playback SHALL occur exactly once through the then-current resolved output

#### Scenario: Queued playback reaches terminal route failure
- **WHEN** a successfully scheduled Debug entry cannot obtain a valid route and host queue policy terminates it with a typed route failure
- **THEN** the host SHALL dispose the queued audio without ambient fallback, duplicate playback, or another output attempt
- **AND** it SHALL NOT retroactively change the input execution status that became successful at queue admission

#### Scenario: Deferred playback route differs from capture route
- **WHEN** capture has terminated and the queued playback becomes eligible under Work, On-a-pinch, or On-the-road
- **THEN** Work playback SHALL use an acquired Bluetooth SCO communication route with voice-communication usage
- **AND** On-a-pinch playback SHALL use normal audio mode and the built-in speaker with media usage
- **AND** On-the-road playback SHALL release any stale Work (RSM) route, wait for Telecom capture routing to be released and normal audio mode, then play through the car media path (a validated car media output when enumerable, otherwise unpinned `USAGE_MEDIA` default media routing to the A2DP sink), requesting transient media audio focus best-effort
- **AND** none of these paths SHALL reuse a released PTT-session route or choose an ambient fallback output

#### Scenario: On-a-pinch admission under owned Work SCO residue
- **WHEN** a queued Debug entry becomes eligible in On-a-pinch mode while the app's own Work SCO lease or keep-warm residue still holds the communication route
- **THEN** phone playback acquisition SHALL NOT treat that owned residue as a busy foreign route
- **AND** it SHALL resolve the built-in speaker with media usage
- **AND** only a communication route the app does not own SHALL make phone acquisition busy

### Requirement: Debug Channel is an external Lua package
The Debug Channel SHALL be published as an external, source-only Lua v1 package from the official `talkcan/debug-channel` GitHub repository under the official owner database ID `1224006`. Its manifest SHALL satisfy package-format v1 exactly, including `manifestVersion = 1`, the required nested `runtime.luaVersion` and `runtime.apiVersion` fields with the host's exact supported v1 values, explicit configuration and capabilities declarations, and no unknown root keys. The release archive SHALL contain only `manifest.json` plus canonical UTF-8 Lua source modules. The host application SHALL NOT bundle the Debug Channel package or register a built-in fallback. Package installation, instance creation, updates, rollbacks, removal, and restart recovery SHALL run exclusively through the generic installed-package and runtime pathways.

#### Scenario: Debug Channel package is installed
- **WHEN** the application validator inspects a published Debug Channel release asset
- **THEN** the package SHALL pass validation checks including manifest keys, owner ID matching, and digest verification
- **AND** the host SHALL register its provider descriptor without starting any Lua execution or constructing an actor

#### Scenario: User creates a Debug Channel instance
- **WHEN** the user creates a channel instance through the catalogue using the registered external Debug provider
- **THEN** the system SHALL create a catalogue definition with a unique opaque instance ID and the provider reference
- **AND** the configuration SHALL be persisted as a versioned opaque payload

#### Scenario: Debug Channel package is updated
- **WHEN** a newer Debug Channel package revision is installed and published
- **THEN** the host SHALL stop each predecessor generation and cancel its pending operations
- **AND** it SHALL validate each preserved instance payload against the new revision before runtime construction
- **AND** it SHALL construct a fresh successor generation with the new revision and validated startup snapshot only for a compatible enabled instance
- **AND** an incompatible instance SHALL remain preserved and explicitly unavailable without default substitution, coercion, or successor construction

#### Scenario: Debug Channel package is rolled back
- **WHEN** the user rolls back the Debug Channel package to the previous installed version
- **THEN** the host SHALL replace the active generation with a fresh generation created from the rolled-back package asset
- **AND** no predecessor callbacks, timers, or execution effects SHALL be carried forward

#### Scenario: Debug Channel package is removed
- **WHEN** the user removes the installed Debug Channel package
- **THEN** all running generations of the package SHALL be terminated immediately
- **AND** its catalogue definitions SHALL remain persisted in the catalogue as unavailable records

#### Scenario: Application is restarted
- **WHEN** the host application or foreground service is restarted with an enabled Debug Channel instance
- **THEN** the host SHALL construct a fresh runtime generation
- **AND** the host SHALL NOT restore any prior Lua state, sequence numbers, timers, or coroutines

### Requirement: Debug configuration defines flat mode options
The Debug Channel manifest SHALL declare configuration schema version `1` with one required string data field `mode`, default `"ECHO"`, and exact allowed values `ECHO`, `DELAYED_ECHO`, `STT`, `TTS`, and `STT_TTS`. Its UI declaration SHALL map `mode` to one `choice` control whose value and label entries match those allowed values exactly. At startup the host SHALL deliver a detached snapshot of the validated configuration to mandatory `startup(configuration)`. The host SHALL NOT mutate persisted configuration in place; an accepted edit SHALL use generic atomic catalogue commit and generation replacement.

#### Scenario: Startup receives validated configuration
- **WHEN** a Debug Channel generation starts up
- **THEN** the host SHALL invoke `startup` with a table containing `schema_version = 1` and a `values` table with `mode` matching the persisted value
- **AND** the Lua actor SHALL be able to copy or read the configuration values locally without mutating the host configuration

#### Scenario: Instance configuration is edited
- **WHEN** the user updates the Debug Channel instance through the generic host-rendered `choice` control
- **THEN** the system SHALL validate the new value, commit the catalogue change, stop the predecessor generation, and start a fresh successor generation with the new configuration snapshot
- **AND** the predecessor generation's queued playbacks and operations SHALL be cancelled

#### Scenario: Invalid configuration is rejected
- **WHEN** the host receives a catalogue update containing an invalid value or type for the `mode` configuration field
- **THEN** the host SHALL reject the update
- **AND** the persisted catalogue and running generation SHALL remain unchanged

### Requirement: Debug readiness and execution reflect selected mode capabilities
The Debug Channel package SHALL declare eligibility for the public capabilities `audio.transcription`, `audio.synthesis`, and `audio.playback`. The `handle_readiness` callback SHALL evaluate the availability of only the capabilities required by the selected configuration mode, returning a status summary string matching the selected mode. API callback execution and capability modules SHALL enforce strict authorization, returning normalized, typed error tables rather than throwing exceptions. The host SHALL track input execution through transitions: `IDLE` -> `RECORDING` -> `PROCESSING` -> `SUCCESS` or `FAILED`.

#### Scenario: Readiness depends on selected mode capabilities
- **WHEN** `handle_readiness` is invoked with a capability availability snapshot
- **THEN** it SHALL return `{ready = true, status = <mode>}` only if the selected mode's dependency set is satisfied
- **AND** the dependency set SHALL require `audio.playback` for `ECHO` and `DELAYED_ECHO`; `audio.transcription` for `STT`; `audio.synthesis` and `audio.playback` for `TTS`; and all three capabilities for `STT_TTS`

#### Scenario: Execution status flow succeeds
- **WHEN** a selected ready Debug instance receives PTT input
- **THEN** the host execution status SHALL progress from `IDLE` to `RECORDING` during audio capture
- **AND** it SHALL progress to `PROCESSING` when the input is delivered to `handle_input`
- **AND** it SHALL transition to `SUCCESS` when `handle_input` returns its successful terminal result; successful deferred-queue admission completes `playback.schedule`, and later physical playback SHALL NOT keep or change the input execution status

#### Scenario: Execution status flow fails
- **WHEN** a Debug callback fails due to a capability error or execution failure
- **THEN** the host execution status SHALL transition from `PROCESSING` to `FAILED`
- **AND** the host SHALL execute its neutral failure response path without mode fallback

#### Scenario: Undeclared capability access fails
- **WHEN** the Debug Channel actor calls a module whose capability was not declared in the package manifest
- **THEN** the module call SHALL immediately return `nil` and the typed error code `E_CAPABILITY_UNDECLARED`
- **AND** the call SHALL NOT execute any host action

#### Scenario: Input session is cancelled
- **WHEN** the user cancels capture or the host aborts an active session
- **THEN** any pending yielding operation in the actor SHALL resume with the typed error code `E_CANCELLED`
- **AND** the execution status SHALL transition to `IDLE`

### Requirement: Debug modes implement echo, transcription, and synthesis behaviors
The Debug Channel package SHALL implement functional behaviors for all five modes in its `handle_input` callback using opaque audio userdata and public yielding modules. `ECHO` and `TTS` modes SHALL schedule playback immediately. `DELAYED_ECHO` SHALL schedule playback with a 5.0 seconds delay using host queue eligibility rather than Lua-side sleep, maintaining FIFO ordering. `STT` SHALL transcribe input audio and return success without playback. `STT_TTS` SHALL transcribe, synthesize the transcript, and schedule playback.

#### Scenario: ECHO mode schedules immediate playback
- **WHEN** `handle_input` is called in `ECHO` mode
- **THEN** the actor SHALL call `talkcan.playback.schedule` with the input captured audio and `delay_seconds = 0`
- **AND** the host SHALL accept the audio into the queue and return a scheduled status
- **AND** `handle_input` SHALL return `{ok = true}`

#### Scenario: DELAYED_ECHO mode enforces FIFO queue delay
- **WHEN** two consecutive PTT inputs are captured in `DELAYED_ECHO` mode
- **THEN** the actor SHALL schedule each input audio with `delay_seconds = 5.0`
- **AND** the host queue SHALL manage the eligibility time of the entries without yielding the Lua execution via sleep
- **AND** the entries SHALL remain in FIFO queue order, playing in sequence without letting the second entry overtake the first

#### Scenario: STT mode transcribes input without playback
- **WHEN** `handle_input` is called in `STT` mode
- **THEN** the actor SHALL call `talkcan.transcription.transcribe` with the captured audio
- **AND** it SHALL receive the text transcript and log it via `talkcan.log`
- **AND** `handle_input` SHALL return `{ok = true}` without scheduling playback

#### Scenario: TTS mode synthesizes speech from fixed text
- **WHEN** `handle_input` is called in `TTS` mode
- **THEN** the actor SHALL call `talkcan.synthesis.synthesize` with text `"Debug synthesis test"`, language `"en"`, voice `"default"`, and speed `1.0`
- **AND** it SHALL schedule the resulting opaque synthesized audio with `delay_seconds = 0`
- **AND** `handle_input` SHALL return `{ok = true}`

#### Scenario: STT_TTS mode transcribes and synthesizes transcript
- **WHEN** `handle_input` is called in `STT_TTS` mode
- **THEN** the actor SHALL transcribe the input audio via `talkcan.transcription.transcribe`
- **AND** it SHALL synthesize the resulting text transcript via `talkcan.synthesis.synthesize` with language `"en"`, voice `"default"`, and speed `1.0`
- **AND** it SHALL schedule the synthesized audio via `talkcan.playback.schedule` with `delay_seconds = 0`

### Requirement: Debug execution is lifecycle-bound and revocable
Every asynchronous operation and audio userdata reference created during a Debug Channel invocation SHALL be bound to that specific invocation and runtime generation. Unconsumed audio references SHALL be disposed by the host upon invocation termination. Generation close, replacement, or disablement SHALL trigger immediate revocation of the generation's capability leases, invalidation of its audio userdata references, cancellation of all pending host operations, deletion of its queued playback entries, and suppression of all late callbacks, logs, or playback effects.

#### Scenario: Generation close invalidates audio userdata
- **WHEN** a Debug Channel generation is closed or replaced while a coroutine holds a reference to an audio userdata
- **THEN** the host audio registry SHALL immediately remove that reference's token mapping
- **AND** any subsequent attempted host operation using that userdata SHALL return `E_CLOSED`

#### Scenario: Revocation removes queued playback
- **WHEN** the Debug Channel instance is disabled, removed, replaced, or otherwise has its runtime generation revoked while scheduled playback remains queued
- **THEN** the host SHALL remove every queued playback entry authorized by that generation
- **AND** those entries SHALL NOT play through any output

#### Scenario: Temporary selection change retains queued playback
- **WHEN** another channel is selected while a live Debug generation has scheduled playback pending
- **THEN** the host SHALL retain the pending entries without playing them through the other channel's output
- **AND** it SHALL re-evaluate and admit them under current host selection and routing policy if Debug is selected again while the generation remains authorized

#### Scenario: Unconsumed audio userdata is cleaned up
- **WHEN** a `handle_input` invocation terminates
- **THEN** the host SHALL dispose of all opaque audio recordings and synthesized audio references created during that invocation that were not successfully scheduled for playback
- **AND** no memory or resource leak SHALL occur
