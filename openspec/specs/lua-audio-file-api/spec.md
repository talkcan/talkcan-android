## Purpose

Defines generic audio-file operations over `talkcan.audio`: bounded recording description, WAV/PCM export into mounted storage, and reopening stored WAV recordings as opaque execution-owned Recordings without exposing PCM, file descriptors, or platform locations.

## Requirements

### Requirement: `talkcan.audio` exposes generic recording file operations
The host SHALL reserve and inject `talkcan.audio` for every package. The module SHALL expose exactly `describe`, `export`, and `open`. Requiring the module SHALL NOT grant `audio.files`, mounted-storage eligibility, or a resource binding. `describe` SHALL be a bounded synchronous registry lookup. `export` and `open` SHALL be yielding and callable only from `handle_input` or a runtime-managed task. Module evaluation SHALL reject calls through the effect-call-during-load guard; ineligible callbacks and unmanaged coroutines SHALL receive `E_INVALID_CONTEXT` before effects.

#### Scenario: Eligible task opens a recording
- **WHEN** a package declaring `audio.files` and `storage.files` calls `audio.open` from a managed task with a live readable mount
- **THEN** the task SHALL yield and resume exactly once with an opaque Recording or normalized error

#### Scenario: Package requires module without declaring capability
- **WHEN** a package can require `talkcan.audio` but did not declare `audio.files`
- **THEN** an operation SHALL return `E_CAPABILITY_UNDECLARED` before mount or storage work

### Requirement: Recording description exposes bounded media metadata only
`audio.describe(recording)` SHALL accept one live Recording userdata owned by the current execution and return exact nonnegative `sample_rate`, `channels`, `duration_ms`, and `pcm_bytes`. It SHALL NOT decode a file, yield, transfer ownership, return raw samples, expose a registry token, or accept Synthesized audio in v1.

#### Scenario: Captured recording is described
- **WHEN** `handle_input` describes its live captured Recording
- **THEN** Lua SHALL receive bounded scalar media metadata
- **AND** the Recording SHALL remain live and unconsumed

#### Scenario: Foreign recording is described
- **WHEN** a task passes a Recording owned by another execution
- **THEN** description SHALL return `E_INVALID_ARGUMENT` before revealing metadata

### Requirement: Recording export supports exact portable formats
`audio.export(recording, mount, relative_path, options)` SHALL accept one live current-execution Recording, one live writable directory-tree mount, one valid logical path, and an exact-key options table whose `format` is exactly `wav-pcm-s16le`. The operation SHALL borrow rather than consume the Recording. It SHALL validate context, declarations, mount access, path, artifact bounds, and operation capacity before effect where possible. Success SHALL return exact `{status="written", format="wav-pcm-s16le", sample_rate, channels, duration_ms, bytes}` only when the complete destination is visible.

#### Scenario: Captured recording is exported as WAV
- **WHEN** an eligible input callback exports its captured Recording with format `wav-pcm-s16le`
- **THEN** the host SHALL write a complete mono PCM WAV document under the authorized mount
- **AND** the original Recording SHALL remain usable by that input execution

#### Scenario: Unsupported format is requested
- **WHEN** `format` is missing, unknown, or has the wrong type
- **THEN** export SHALL return `E_INVALID_ARGUMENT` before creating a destination

### Requirement: Stored PCM recordings can be reopened for recovery
`audio.open(mount, relative_path, options)` SHALL accept an exact-key options table with `format="wav-pcm-s16le"`. It SHALL read and validate a bounded complete WAV/PCM document through the mount, reject unsupported channel/sample/encoding values, and create one opaque Recording owned by the calling input invocation or managed task only after complete successful decode and quota admission. V1 SHALL return no partial or truncated audio.

#### Scenario: Managed recovery task opens valid WAV
- **WHEN** a valid bounded PCM WAV exists under an authorized mount
- **THEN** `audio.open` SHALL return a task-owned opaque Recording
- **AND** transcription and WAV export MAY borrow that Recording in the same task

#### Scenario: WAV is malformed or oversized
- **WHEN** the source has an invalid header, unsupported encoding, inconsistent length, or exceeds an artifact/duration/registry bound
- **THEN** opening SHALL fail with `E_INVALID_VALUE`, `E_TOO_LARGE`, or `E_HOST_FAILURE`
- **AND** no Recording token or partial PCM shall be published

### Requirement: Audio-file operations preserve execution and generation ownership
An opened Recording SHALL be registered under exactly the calling execution owner and current generation. It SHALL not transfer to another callback, managed task, state, instance, or generation. Export and transcription SHALL borrow it; execution termination SHALL dispose it; generation close SHALL invalidate it and suppress late open/export completion. Garbage collection SHALL not perform storage, route, or capability effects.

#### Scenario: Deferred task captures input Recording
- **WHEN** a deferred task attempts to use the predecessor input invocation's Recording userdata
- **THEN** registry resolution SHALL reject it as foreign or stale before storage work
- **AND** the task SHALL need to reopen the durable WAV under its own execution owner

#### Scenario: Generation closes during open
- **WHEN** decoding finishes after the owning generation has closed
- **THEN** the host SHALL dispose decoded PCM without creating a live token or resuming Lua

### Requirement: Audio-file failures are normalized and bounded
Expected failures SHALL return `(nil, error_table)` using applicable stable codes `E_INVALID_ARGUMENT`, `E_INVALID_VALUE`, `E_INVALID_PATH`, `E_INVALID_CONTEXT`, `E_CAPABILITY_UNDECLARED`, `E_MOUNT_UNAVAILABLE`, `E_REAUTHORIZATION_REQUIRED`, `E_READ_ONLY`, `E_NOT_FOUND`, `E_EXISTS`, `E_TOO_LARGE`, `E_NO_SPACE`, `E_BUSY`, `E_TIMEOUT`, `E_CANCELLED`, `E_CLOSED`, `E_STALE`, `E_UNSUPPORTED`, `E_IO`, or `E_HOST_FAILURE`. Optional reasons SHALL be bounded and contain no platform location, exception, pointer, or provider identity.

#### Scenario: WAV publication fails unexpectedly
- **WHEN** the host cannot publish a complete WAV document for an implementation-specific reason
- **THEN** Lua SHALL receive a normalized failure without diagnostic leakage
- **AND** newly allocated audio resources and incomplete destinations SHALL be cleaned according to storage backend semantics
