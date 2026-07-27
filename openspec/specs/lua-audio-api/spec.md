## Purpose

Defines public Lua v1 opaque captured/synthesized audio values and semantic transcription, synthesis, and deferred playback operations with typed asynchronous lifecycle behavior.

## Requirements

### Requirement: Audio values are unforgeable, generation-owned, execution-scoped native full-userdata
The Lua environment SHALL represent Recording and Synthesized audio as private host-constructed native full-userdata with locked metatables and no public fields/methods. A Recording MAY originate from host capture or successful `talkcan.audio.open`; origin SHALL not be inspectable through userdata. A value SHALL contain only a state-local opaque token and kind tag and SHALL NOT contain PCM, filesystem paths, mount authority, device/route identity, JVM/native pointers, capability leases, or public operation identities. The host SHALL maintain one registry per Lua state/generation; each token SHALL map to exactly one host-owned opaque Recording or Synthesized artifact and one execution owner. Foreign state, instance, generation, callback, or task tokens SHALL be rejected before effects.

The host SHALL enforce finite positive limits on one artifact's retained bytes/duration and live token count/bytes per execution owner, generation, and process. Capture injection, stored-recording open, or synthesis that cannot fit SHALL dispose the artifact and fail before userdata delivery. Oversized completed artifacts SHALL be disposed without token publication. Lua heap limits SHALL not substitute for host audio-resource limits.

#### Scenario: Audio userdata is locked
- **WHEN** Lua reads/writes a field, calls a method, or retrieves the real metatable
- **THEN** locked-metatable behavior SHALL reject it
- **AND** Lua SHALL not obtain token, origin, pointer, path, route, mount, or PCM

#### Scenario: Audio userdata is rejected by normalization and logging
- **WHEN** a callback returns audio userdata, passes it to `talkcan.log`, or includes it in configuration/error data
- **THEN** normalization SHALL reject the whole payload with `E_INVALID_VALUE`
- **AND** no partial serialization or log SHALL occur

#### Scenario: Garbage collection does not own host lifetime
- **WHEN** audio userdata is collected
- **THEN** collection SHALL not release routes, cancel operations, remove files, or otherwise operate the resource

#### Scenario: Foreign audio userdata is rejected
- **WHEN** an operation receives userdata owned by another state, instance, generation, callback, or task
- **THEN** resolution SHALL fail before effects
- **AND** it SHALL return `E_INVALID_ARGUMENT` with bounded language-neutral reason

#### Scenario: Registry capacity is exhausted
- **WHEN** capture, open, or synthesis completion would exceed a token/byte bound
- **THEN** the host SHALL create no userdata token, dispose the rejected artifact, and return the applicable typed failure

#### Scenario: Artifact exceeds individual bound
- **WHEN** a captured, opened, or synthesized artifact exceeds the finite per-artifact byte/duration bound
- **THEN** the host SHALL dispose it without registry publication
- **AND** no callback SHALL receive truncated or partial audio

### Requirement: Recording and Synthesized handles have strict lifetime, borrow, consume, and dispose rules
A capture Recording SHALL be owned by its `handle_input` invocation. An opened Recording SHALL be owned by the input invocation or runtime-managed task that completed `audio.open`. Synthesized audio SHALL be owned by the authorized input/task that completed synthesis. Audio SHALL not transfer between executions. `transcription.transcribe`, `audio.describe`, and `audio.export` SHALL borrow a Recording without consuming it. `playback.schedule` SHALL atomically consume Recording or Synthesized audio only after successful queue admission. Consumed handles SHALL return `E_STALE`; failed playback admission SHALL leave them live. Execution termination SHALL dispose unconsumed audio and invalidate its tokens. Generation close SHALL invalidate all remaining tokens and dispose unscheduled resources.

#### Scenario: Borrowing Recording then scheduling playback
- **WHEN** an input transcribes or exports its Recording and then schedules that Recording
- **THEN** borrowing SHALL leave it live
- **AND** successful playback admission SHALL consume it
- **AND** later use SHALL return `E_STALE`

#### Scenario: Opened Recording is used by owner task
- **WHEN** a managed task opens a WAV and then transcribes/exports it
- **THEN** both operations MAY borrow it within that task
- **AND** another task SHALL not use it

#### Scenario: Failed playback admission leaves handle live
- **WHEN** playback admission fails
- **THEN** the handle SHALL remain unconsumed until owner termination

#### Scenario: Synthesized audio belongs to task
- **WHEN** a managed task synthesizes audio
- **THEN** the result SHALL belong to that task/generation
- **AND** another callback/task SHALL not use it

#### Scenario: Unconsumed audio is disposed on execution termination
- **WHEN** an input or managed task terminates while owning unconsumed audio
- **THEN** the registry SHALL dispose it and invalidate tokens
- **AND** later use SHALL return `E_STALE`

#### Scenario: Generation close invalidates all audio
- **WHEN** a generation retires/closes while any execution owns audio
- **THEN** every mapping SHALL be invalidated and unscheduled audio disposed
- **AND** subsequent use SHALL return `E_CLOSED`

### Requirement: Public audio modules expose bounded yielding semantic operations
The host SHALL inject `talkcan.transcription`, `talkcan.synthesis`, and `talkcan.playback`; generic file operations belong to separately specified `talkcan.audio`. Requiring any module SHALL not grant eligibility. Each call SHALL validate context, arguments, manifest eligibility, live leases, userdata ownership, and bounds before effects.

Semantic operations SHALL be exactly:
- `transcription.transcribe(recording)`: accepts one live Recording owned by the current execution and returns `({text=<bounded UTF-8>}, nil)`.
- `synthesis.synthesize(params)`: accepts exact bounded text/language/voice and optional positive finite speed and returns Synthesized userdata.
- `playback.schedule(audio, options)`: accepts one live Recording or Synthesized value and optional bounded nonnegative delay, returning scheduled status only after atomic queue admission and consumption.

The host SHALL apply finite positive deadlines to transcription, synthesis, and queue admission and finite delay/queue/retained-byte limits. Rejection before admission SHALL have no effect; failed playback admission SHALL leave audio unconsumed. Calls MAY yield only from `handle_input` or a runtime-managed task. Source/module evaluation SHALL fail through the effect guard; synchronous callbacks/unmanaged coroutines SHALL receive `E_INVALID_CONTEXT`. Ordinary errors SHALL remain the exact existing stable semantic-audio vocabulary.

#### Scenario: Successful transcription returns text
- **WHEN** an eligible execution transcribes a live capture-origin or opened Recording within deadline
- **THEN** it SHALL resume exactly once with bounded text

#### Scenario: Invalid synthesis parameters are rejected
- **WHEN** synthesis receives unknown/missing/blank/invalid parameters
- **THEN** it SHALL return `E_INVALID_ARGUMENT` without host synthesis or allocation

#### Scenario: Undeclared capability is denied
- **WHEN** a package lacking the applicable capability calls an operation
- **THEN** it SHALL return `E_CAPABILITY_UNDECLARED` before acquisition/effect

#### Scenario: Module evaluation attempts audio operation
- **WHEN** top-level module evaluation calls an audio function
- **THEN** the effect guard SHALL fail complete evaluation without a normal pair

#### Scenario: Invalid callback context calls audio operation
- **WHEN** readiness or another ineligible callback calls an operation
- **THEN** it SHALL return `E_INVALID_CONTEXT` without suspension/effect

#### Scenario: Playback delay exceeds bound
- **WHEN** delay exceeds the host maximum
- **THEN** scheduling SHALL return `E_INVALID_ARGUMENT` and leave the handle live

#### Scenario: Playback queue lacks capacity
- **WHEN** queue admission exceeds a count/byte bound
- **THEN** scheduling SHALL return `E_BUSY`, leave audio live, and create no entry

### Requirement: Audio operations resolve exactly once under execution-owner and generation authorization
Each admitted semantic or audio-file operation SHALL carry an opaque typed host request, actor operation ID, current execution owner, and generation capability identity. Yielding SHALL suspend only the owner and release the adapter slot. Success, typed failure, deadline, live-input cancellation, task cancellation, mount revocation where applicable, or generation close SHALL race through one atomic terminal gate. Completion MAY resume Lua only while actor, owner, audio token, leases/mount, and generation remain current.

A deadline SHALL cancel/detach backend work and resume a still-live owner once with `E_TIMEOUT`. Timed-out transcription/export borrows SHALL leave the Recording live; timed-out synthesis/open SHALL publish no audio token; timed-out queue admission SHALL create no queue entry and leave input audio live. Late completion SHALL create no token/effect/resumption. Live-input cancellation MAY resume once with `E_CANCELLED`; task cancellation/close SHALL discard suspended execution without re-entry and dispose owner audio. Generation close SHALL invalidate tokens, revoke leases/mounts, remove generation-authorized queued playback, clean staging, and suppress late effects.

A resumed execution MAY admit successive operations under the same execution owner; each receives a distinct request and terminal gate. An earlier terminal gate SHALL not block a later operation.

#### Scenario: Yielding operation releases execution slot
- **WHEN** an authorized operation yields
- **THEN** the actor gate SHALL be released while retaining owner identity
- **AND** unrelated actor work MAY enter Lua

#### Scenario: Operation deadline expires
- **WHEN** an operation exceeds its deadline while owner remains live
- **THEN** the owner SHALL resume exactly once with `E_TIMEOUT`
- **AND** late completion SHALL publish no artifact, token, queue entry, or result

#### Scenario: Live input cancellation resumes E_CANCELLED
- **WHEN** live input is cancelled while suspended
- **THEN** it SHALL resume once with `E_CANCELLED` when bounded delivery remains possible

#### Scenario: Task cancellation discards suspended execution
- **WHEN** a task is cancelled during audio work
- **THEN** the host SHALL cancel work, discard the coroutine, and dispose owner audio
- **AND** late completion SHALL not resume or create effects

#### Scenario: Generation close suppresses late effects
- **WHEN** generation closes with audio work pending
- **THEN** suspended execution and related audio/capability/mount/staging state SHALL be revoked
- **AND** late completion SHALL be stale without Lua re-entry

#### Scenario: Chained operations complete independently
- **WHEN** an execution resumes and yields a further operation before terminating
- **THEN** each operation SHALL claim its own gate under the same owner
- **AND** the execution SHALL terminate exactly once after its final result
