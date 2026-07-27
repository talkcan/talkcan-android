## Purpose

Defines the external portable Lua Keyboard Channel package capability, configuration, readiness, PTT transcription delivery, SOS Enter action, and coexistence with built-in Keyboard.

## Requirements

### Requirement: External Keyboard package uses only public generic contracts
The system SHALL support an external Lua Keyboard Channel package installed from an ordinary validated GitHub release asset. The package SHALL declare only generic package configuration, `audio.transcription`, and `keyboard.output`; its Lua code SHALL own Keyboard-specific readiness, transcription sequencing, trailing-space policy, keyboard delivery, and SOS key policy. The host SHALL NOT branch on the package repository identity, implementation ID, label, source, or channel name and SHALL NOT contain a test-only or production Keyboard-package provider.

#### Scenario: Package is materialized
- **WHEN** the exact external Keyboard artifact validates and is installed
- **THEN** the ordinary package materializer SHALL publish its repository-derived provider without executing Lua
- **AND** no Keyboard-specific Kotlin branch, bundled source, automatic installation, or automatic instance creation SHALL occur

### Requirement: External Keyboard configuration selects a dynamic host profile hierarchically
The package SHALL declare required unconstrained string fields `host_os`, `host_layout`, and `host_profile`, with defaults `linux`, `linux:us`, and `linux:us`. Their UI declarations SHALL resolve through `keyboard-output-platforms`, dependent `keyboard-output-layouts`, and dependent `keyboard-output-profiles` respectively. Each instance SHALL persist only its selected scalar IDs. The host SHALL resolve labels and current membership from the generic dynamic-choice registry, and package startup SHALL receive only the detached scalar configuration snapshot. The package SHALL use only `host_profile` for keyboard output. Two instances SHALL retain independent selections and generations.

#### Scenario: User creates two configured instances
- **WHEN** the user creates two external Keyboard instances with different available final host-profile IDs
- **THEN** each definition SHALL persist its own platform, layout, and profile scalar IDs
- **AND** each runtime generation SHALL use only its own detached configuration

#### Scenario: User selects a host profile
- **WHEN** the user selects a host platform and then a keyboard layout
- **THEN** the editor SHALL resolve only layouts for that platform and only final profile variants for that layout
- **AND** each individual choice result SHALL remain within the global bounded publication limit

#### Scenario: Configured hierarchy no longer resolves
- **WHEN** a required persisted platform, layout, or final profile ID is absent from its current dynamic source
- **THEN** readiness context SHALL report that dynamic reference and its dependents unavailable
- **AND** the package SHALL report not ready without attempting keyboard preparation or output

### Requirement: External Keyboard readiness prepares keyboard output before capture
The package's `handle_readiness` SHALL require a valid configured profile and evaluate `keyboard.output` availability while deliberately excluding `audio.transcription` from PTT admission readiness. When keyboard output is available it SHALL report ready. When keyboard output is recoverable it SHALL report not ready and request preparation of `keyboard.output`. When preparation succeeds, the host SHALL refresh readiness and accept input only if the package then reports ready. No ready beep or capture SHALL begin while preparation is pending or unsuccessful.

#### Scenario: Keyboard output is already available
- **WHEN** the configured profile resolves and `keyboard.output` is available
- **THEN** the package SHALL report ready
- **AND** PTT SHALL proceed without starting another connection attempt

#### Scenario: Keyboard output is recoverable
- **WHEN** the profile resolves and readiness reports `keyboard.output` as recoverable
- **THEN** the package SHALL return `ready = false` with `prepare = {"keyboard.output"}`
- **AND** the host SHALL run one bounded generic preparation before accepting input

#### Scenario: Preparation does not establish readiness
- **WHEN** preparation fails, times out, is cancelled, or completes stale without current keyboard-output availability
- **THEN** the host SHALL refuse the PTT input
- **AND** it SHALL NOT play the ready beep, capture audio, transcribe, or type

#### Scenario: Transcription backend is unavailable before PTT
- **WHEN** keyboard output is available but `audio.transcription` is unavailable
- **THEN** the package SHALL still report ready for PTT admission
- **AND** a later transcription attempt SHALL return the normalized operation failure after capture

### Requirement: External Keyboard PTT transcribes and delivers text
On a completed nonempty capture, the package SHALL transcribe the opaque recording through `talkcan.transcription`, reject an unavailable, failed, malformed, or empty transcription without keyboard output, append exactly one ASCII space only when the transcript does not already end in an ASCII space, and submit the resulting text through `talkcan.keyboard_output.send_text` with that instance's configured profile. It SHALL return exact input success only after a `delivered` outcome and SHALL return an application failure for rejected, failed, indeterminate, cancelled, timed-out, or malformed outcomes without automatic replay.

#### Scenario: Transcript lacks trailing space
- **WHEN** transcription returns `captured text`
- **THEN** the package SHALL submit exactly `captured text ` using the configured profile
- **AND** it SHALL return input success only after complete delivery is acknowledged

#### Scenario: Transcript already ends in space
- **WHEN** transcription returns `captured text `
- **THEN** the package SHALL submit that text unchanged
- **AND** it SHALL NOT append another space

#### Scenario: Transcription fails or is empty
- **WHEN** transcription fails, returns malformed data, or returns an empty string
- **THEN** the package SHALL return one application failure
- **AND** it SHALL NOT invoke keyboard output

#### Scenario: Delivery is indeterminate
- **WHEN** keyboard output reports that partial delivery cannot be excluded
- **THEN** the package SHALL return an application failure identifying uncertain delivery without including text or transport details
- **AND** it SHALL NOT retry any part of the transcript

### Requirement: External Keyboard SOS sends one semantic Enter
The package SHALL implement yield-capable `handle_sos` by validating the SOS event and invoking `talkcan.keyboard_output.send_key` exactly once with key `enter` and the instance's configured profile. It SHALL return exact success only after delivered acknowledgement and SHALL return a bounded application failure for every non-delivered outcome without replay.

#### Scenario: SOS Enter is delivered
- **WHEN** the host dispatches SOS to a live external Keyboard instance and Enter is acknowledged
- **THEN** the package SHALL submit exactly one semantic `enter` operation using that instance's profile
- **AND** the SOS callback SHALL terminate successfully

#### Scenario: SOS generation is revoked while suspended
- **WHEN** the instance generation closes or is replaced while SOS Enter is pending
- **THEN** predecessor output authority SHALL be revoked
- **AND** no late completion SHALL resume predecessor Lua or emit another key

### Requirement: External and built-in Keyboard providers coexist independently
This change SHALL retain `builtin:keyboard`, its provider, catalogue seed, configuration, runtime behavior, and tests while adding the external repository-derived provider. Installation SHALL NOT copy, rebind, alias, rename, select, disable, delete, or mutate a built-in definition. Both provider types and all instances SHALL share host-owned Sleepwalker transport serialization without owning or closing it themselves.

#### Scenario: External package is installed beside built-in Keyboard
- **WHEN** a catalogue contains `builtin:keyboard` and the external provider is installed
- **THEN** the built-in definition and active selection SHALL remain unchanged
- **AND** the user SHALL explicitly create and configure each external instance

#### Scenario: Built-in and external instances output concurrently
- **WHEN** built-in and external instances submit keyboard output concurrently
- **THEN** the host SHALL serialize both through the shared transport policy
- **AND** neither runtime SHALL own, duplicate, or close the shared connection

### Requirement: External Keyboard package is published and verified as ordinary package data
The official package SHALL be published from `talkcan-channels/keyboard` under the configured official owner identity, with its resolved positive immutable repository database ID in the manifest and one deterministic source-only `talkcan-channel.zip` release asset. Local candidate bytes SHALL pass the real validator, store, materializer, provider registry, catalogue, runtime registry, actor, and capability path before publication. Public acceptance SHALL record exact repository, release, asset, size, digest, and timestamp provenance and SHALL install the downloaded asset through the production package-management path.

#### Scenario: Local candidate is validated before publication
- **WHEN** deterministic candidate bytes are assembled
- **THEN** the application SHALL validate and execute them through the ordinary installed-provider path before any release is published
- **AND** provider inspection and registration SHALL create no Lua state

#### Scenario: Public package is installed
- **WHEN** the published asset is anonymously resolved and downloaded through the production GitHub path
- **THEN** its durable identities, size, and SHA-256 SHALL match the recorded provenance
- **AND** the app SHALL contain no bundled copy, repository-name branch, automatic installation, or automatic instance
