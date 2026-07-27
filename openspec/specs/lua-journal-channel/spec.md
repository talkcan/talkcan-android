## Purpose

Defines the external, non-bundled Lua Journal package: publication identity, declared resources and configuration, coexistence with `builtin:journal`, the Lua-owned durable state model, capture-commit-before-derivation ordering, derived artifacts, Markdown projection, bounded startup recovery, and installed-package-path verification.

## Requirements

### Requirement: Journal is published as an external non-bundled Lua package
The canonical package SHALL be published from `talkcan-channels/journal` under the existing official owner database ID `1224006`. Its manifest repository ID SHALL equal the positive immutable repository database ID resolved after repository creation. Release `v1.0.0` SHALL be stable, non-draft, non-prerelease, and contain exactly one canonical `talkcan-channel.zip` asset with the revised manifest v1 and source-only Lua modules. The application SHALL not bundle package source or bytes, automatically install it, automatically create an instance, or add repository/name/implementation-specific production dispatch.

#### Scenario: Public Journal package is installed
- **WHEN** the user installs the exact public release asset through generic GitHub package management
- **THEN** the host SHALL validate, store, materialize, register, and reconcile it through the ordinary installed-provider path
- **AND** its implementation ID SHALL be `github-repository:<resolved repository ID>`

#### Scenario: Application starts without Journal package installed
- **WHEN** the installed package store contains no external Journal revision
- **THEN** no external Journal provider or instance SHALL be synthesized
- **AND** `builtin:journal` SHALL remain independently available

### Requirement: External Journal declares portable resources and configuration
The package SHALL declare one required read-write directory-tree mount with ID `output`, public capabilities `storage.files`, `audio.files`, and `audio.transcription`, and schema-version-1 scalar configuration containing exactly one `output_mode` choice. Allowed values SHALL be `VOICE`, `TRANSCRIPT`, and `VOICE_AND_TRANSCRIPT`, with default `VOICE_AND_TRANSCRIPT`. Lua SHALL map that choice to requested voice and transcript outputs. The package SHALL not receive or persist a platform path, URI, bookmark, built-in Journal configuration, or both-false output state.

#### Scenario: User creates default Lua Journal instance
- **WHEN** an instance is created with the package default configuration and a valid `output` mount
- **THEN** readiness SHALL require storage files, audio files, transcription, and the mount according to the default voice-and-transcript mode

#### Scenario: Output mode is voice only
- **WHEN** configuration selects `VOICE`
- **THEN** the package SHALL retain the committed WAV recording and skip transcription
- **AND** no deferred media conversion SHALL be required

### Requirement: Built-in and Lua Journal coexist without shared identity or storage
This change SHALL preserve `builtin:journal`, its provider registration, catalogue seed, configuration, raw-path storage, all-files permission, bootstrap behavior, runtime, recovery, and tests. The external package SHALL use a distinct provider identity and user-selected mount. Supported side-by-side evaluation SHALL configure distinct physical output roots and independently named instances. The host SHALL not copy configuration, reuse instance identity, rebind active selection, infer that a SAF tree equals a built-in raw path, or coordinate writers across implementations.

#### Scenario: Both implementations are enabled
- **WHEN** the user enables one built-in Journal instance and one external Lua Journal instance with distinct roots
- **THEN** selecting and using either instance SHALL write only through its own runtime and configured root
- **AND** failure or replacement of one SHALL not alter the other

#### Scenario: Roots may overlap
- **WHEN** evaluation cannot establish that the raw-path and SAF selections are distinct
- **THEN** side-by-side acceptance SHALL fail as unsupported
- **AND** the host SHALL not guess platform-specific storage identity

### Requirement: Lua owns the complete Journal path and durable state model
The package SHALL derive its directory hierarchy, entry ID, filenames, metadata schema, and state transitions entirely in Lua using the authoritative capture timestamp, event session ID, runtime instance ID, generic filesystem API, and package-local pure-Lua JSON. Entries SHALL use `YYYY/YYYY-MM/YYYY-MM-DD/entries/<entry-id>/`. Authoritative metadata SHALL be a sequence of fixed-width, create-new, complete JSON snapshots under `states/`; every snapshot SHALL include sequence, schema version, entry identity, capture timestamp/offset, channel instance identity, requested outputs, capture state, and transcript state. The host SHALL contain no Journal-specific path, metadata, renderer, or recovery logic for this package.

#### Scenario: New entry is committed
- **WHEN** `handle_input` receives a completed capture
- **THEN** Lua SHALL create an initial state snapshot, export the complete capture as `recording.wav`, create a capture-finished snapshot, admit any required deferred transcript or projection work, and return success in that order
- **AND** no host Journal capability SHALL be invoked

#### Scenario: Metadata snapshot is interrupted
- **WHEN** a create-new snapshot is absent, malformed, or not the next contiguous sequence
- **THEN** recovery SHALL use the highest valid contiguous sequence
- **AND** it SHALL not treat malformed trailing state as committed

### Requirement: Capture success precedes detached derived work
The input callback SHALL report `{ok=true}` only after the complete `recording.wav` and capture-finished snapshot are durably visible and any required deferred work has been admitted. Transcription, Markdown regeneration, and transcript-only WAV cleanup SHALL execute in the deferred task or startup recovery task. Deferred work SHALL reopen the WAV under its own execution owner and SHALL not retain the input Recording. A derived-work failure SHALL be recorded in package metadata and logs without changing the already terminal input success.

#### Scenario: Successful input defers derivation
- **WHEN** WAV export and capture-finished snapshot creation succeed and `runtime.defer` admits required work
- **THEN** the callback SHALL return `{ok=true}`
- **AND** the deferred task SHALL begin only after that terminal success

#### Scenario: Defer admission is busy
- **WHEN** durable capture commit succeeds but required defer returns `E_BUSY`
- **THEN** the package SHALL either complete the required work inline before returning or return a typed application failure while preserving recoverable pending state
- **AND** it SHALL not report a deferred task that was not admitted

### Requirement: Derived outputs and cleanup follow requested terminal states
For requested voice output, the durably committed `recording.wav` SHALL be the final media artifact and capture completion SHALL satisfy the voice request without a conversion state. For a requested transcript, Lua SHALL reopen a task-owned Recording, append transcription-running, call generic transcription, and append transcription-finished with bounded text or transcription-failed. Unrequested transcription SHALL be `skipped`. The WAV SHALL be retained when voice is requested; in `TRANSCRIPT` mode it SHALL be removed only after transcription finishes successfully and durable state proves cleanup eligibility. A transcription failure SHALL retain the WAV for retry and recovery.

#### Scenario: Voice and transcript both finish
- **WHEN** `VOICE_AND_TRANSCRIPT` capture is committed and transcription finishes
- **THEN** Lua SHALL retain `recording.wav` as the final voice artifact
- **AND** durable state SHALL mark the transcript terminal without scheduling media conversion

#### Scenario: Transcript-only work finishes
- **WHEN** `TRANSCRIPT` capture is committed and transcription finishes successfully
- **THEN** Lua SHALL remove `recording.wav` only after the terminal transcript snapshot is visible
- **AND** durable state SHALL preserve proof that cleanup is eligible

#### Scenario: Transcription fails
- **WHEN** requested transcription fails
- **THEN** Lua SHALL record the typed failure, retain `recording.wav`, and leave the entry recoverable
- **AND** it SHALL not claim transcript success

### Requirement: Daily Markdown is a reproducible Lua-owned projection
The package SHALL discover valid nondeleted entries for a day, order them by captured timestamp and entry ID, and render `journal-day-YYYY-MM-DD.md` entirely in Lua. The document SHALL include the day heading, one entry heading per valid entry, transcript text when finished, and a relative WAV link when voice output was requested and capture finished. Markdown replacement SHALL be treated as nonauthoritative: interruption or malformed projection SHALL not change entry state, and startup recovery SHALL regenerate affected days from authoritative snapshots.

#### Scenario: Derived entry updates Markdown
- **WHEN** an entry gains a finished transcript or committed voice recording
- **THEN** Lua SHALL regenerate the containing day projection with the new content and stable ordering

#### Scenario: Markdown replacement is interrupted
- **WHEN** the process stops during projection replacement
- **THEN** entry snapshots and media SHALL remain authoritative
- **AND** the next startup SHALL regenerate the day document

### Requirement: Startup recovery is complete and bounded
Successful startup SHALL admit a managed recovery task before readiness publication completes. That task SHALL traverse the package hierarchy with paginated filesystem operations, validate snapshot sequences, classify interrupted capture or transcript work, reset or supersede interrupted transcription states, resume pending or retryable transcription from retained WAV files, clean transcript-only WAV artifacts after successful transcription, and regenerate changed day projections. Recovery SHALL NOT schedule media conversion. Recovery SHALL be idempotent, bounded by runtime/storage limits, cancellation-aware, and reconstruct all volatile state without a prior Lua state.

#### Scenario: Process stopped during transcription
- **WHEN** restart finds transcription-running without a later terminal snapshot and a valid WAV recording
- **THEN** recovery SHALL append a new retry or pending transition and attempt transcription through a newly opened task-owned Recording

#### Scenario: Process stopped before capture commit
- **WHEN** restart finds an initial snapshot but no valid capture-finished snapshot
- **THEN** recovery SHALL classify the entry as abandoned or failed according to package policy
- **AND** it SHALL ignore or remove any uncommitted partial audio best-effort

#### Scenario: Recovery task is cancelled by replacement
- **WHEN** configuration or mount replacement closes the generation during recovery
- **THEN** the task SHALL stop without publishing late snapshots into the successor execution
- **AND** the successor SHALL restart recovery from durable state

### Requirement: Journal behavior is verified through the installed package path
Automated acceptance SHALL install and materialize exact package bytes, create instances through the generic catalogue/editor, execute all output modes through the real Lua actor, mounted storage, audio-file APIs, transcription capability, and runtime registry, and verify restart, configuration replacement, grant revocation/repair, multiple-instance isolation, malformed state, operation failure, and package update/rollback. Journal-domain unit tests SHALL live in the package repository or execute its exact package sources; app-side generic API tests SHALL use non-Journal fixtures. Physical evidence SHALL compare built-in and Lua instances with distinct roots and record provider identity, artifact digest, generation, mode, terminal ordering, outputs, recovery, and Markdown.

#### Scenario: Two Lua Journal instances are independent
- **WHEN** two instances use distinct mount bindings and modes
- **THEN** each SHALL maintain isolated actors, state, files, readiness, and recovery
- **AND** neither SHALL access the other's mount

#### Scenario: Physical side-by-side capture succeeds
- **WHEN** the user selects each implementation in turn and completes PTT capture
- **THEN** evidence SHALL show each provider writing only to its distinct root with the expected WAV, transcript, and Markdown behavior
- **AND** the external path SHALL contain no built-in Journal capability invocation
