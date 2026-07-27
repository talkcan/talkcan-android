## Purpose

Modifies Channel Host Capabilities to adapt transcription, synthesis, and playback to language-neutral opaque interfaces, and support selection-aware host-routed deferred audio queues.
## Requirements
### Requirement: Channels access effects only through semantic host capabilities
The host SHALL expose Android- and product-native effects to channel runtimes only through semantic capability contracts. The host SHALL retain exclusive ownership of Android resources, audio routes and capture, Bluetooth and other hardware transports, connection and reconnection policy, concurrency, cleanup, and detailed platform diagnostics. Effects including audio capture, playback, text output, Sleepwalker transport, and synthesis SHALL continue to require semantic host capabilities, and capability contracts SHALL NOT expose Android, BLE, GATT, HID transport, Telecom, audio-route, recorder, file-descriptor, socket, or hardware objects to providers or runtimes. The host SHALL NOT expose the removed built-in-only capabilities (`journal`, `openai-model-discovery`, `openai-completion`, `asynchronous-conversation`, `delayed-playback`); those effects are no longer host-provided. General runtime modules for future language adapters are a distinct mechanism: a runtime module SHALL yield opaque, lifecycle-bound operations and SHALL NOT expose platform objects, Android contexts, SDK clients, transport connections, or credential material. This change SHALL NOT introduce HTTP, filesystem, socket, package, or public API contracts for runtime modules.

#### Scenario: Runtime requests a semantic operation
- **WHEN** a runtime needs an effect supported by the host
- **THEN** it SHALL invoke a semantic host capability using host-domain values or opaque handles
- **AND** the host SHALL select and operate the required platform resources and policy internally

#### Scenario: Runtime is created
- **WHEN** the host constructs a provider runtime
- **THEN** it SHALL supply only the semantic capabilities authorized for that runtime instance
- **AND** it SHALL NOT inject an Android context, platform service, hardware client, transport connection, or reconnect controller

#### Scenario: Android-native effect requires a host capability
- **WHEN** a runtime requests an Android- or product-native effect such as audio capture, playback, text output, or synthesis
- **THEN** the effect SHALL be mediated exclusively through a semantic host capability
- **AND** the runtime SHALL NOT receive or control the underlying Android, Bluetooth, Telecom, recorder, or transport object

#### Scenario: Removed built-in-only capability is not exposed
- **WHEN** a runtime or configuration surface requests the journal, openai-model-discovery, openai-completion, asynchronous-conversation, or delayed-playback capability
- **THEN** the host SHALL NOT provide that capability
- **AND** it SHALL treat the request as undeclared or unsupported rather than supplying a host effect

#### Scenario: Future runtime module yields opaque operations
- **WHEN** a future general runtime module provides an operation to a language adapter
- **THEN** the module SHALL yield an opaque, lifecycle-bound operation that the host can cancel, revoke, or drain
- **AND** the module SHALL NOT expose a platform object, Android context, SDK client, socket, file descriptor, transport connection, or credential to the adapter

#### Scenario: Runtime module does not bypass host capabilities
- **WHEN** a runtime module and a semantic host capability both relate to the same concern
- **THEN** the module SHALL NOT expose raw platform resources that the host capability owns
- **AND** Android- and product-native effects SHALL continue to require the corresponding semantic host capability

### Requirement: Capability acquisition is explicit and instance scoped
A runtime SHALL acquire each host capability through its own instance-scoped capability scope. The host SHALL associate every acquired capability and operation with the channel instance ID and the current runtime generation, SHALL enforce descriptor-declared eligibility, and SHALL return a typed unavailable or denied result when acquisition cannot be satisfied. A runtime SHALL NOT obtain capabilities through ambient globals or another instance's scope.

#### Scenario: Authorized capability is acquired
- **WHEN** a live runtime requests a capability declared by its provider and available from the host
- **THEN** the host SHALL return an opaque capability lease bound to that runtime instance and generation
- **AND** operations through that lease SHALL be attributed to the same instance

#### Scenario: Capability is unavailable
- **WHEN** an authorized capability cannot currently be provided
- **THEN** acquisition SHALL return a typed unavailable result with a semantic reason
- **AND** the host SHALL NOT expose the underlying platform object or transport failure object

#### Scenario: Runtime requests an undeclared capability
- **WHEN** a runtime requests a capability not declared for its provider
- **THEN** the host SHALL deny acquisition
- **AND** it SHALL NOT grant access through another instance, a global singleton, or an implementation-specific fallback

### Requirement: Capability leases are revocable and lifecycle bound
Every acquired capability lease SHALL be revocable by the host and SHALL be bounded by the acquiring runtime generation. Runtime replacement, retirement, or closure SHALL revoke its leases and cancel or terminate instance-owned capability operations according to the capability contract. Revocation and release SHALL be idempotent, and use after revocation SHALL fail without producing an effect.

#### Scenario: Runtime is replaced
- **WHEN** a runtime generation is retired because its definition is updated
- **THEN** the host SHALL revoke that generation's capability leases
- **AND** the replacement generation SHALL acquire separate leases even when it has the same channel instance ID

#### Scenario: Revoked lease is used
- **WHEN** a runtime invokes an operation through a lease after revocation
- **THEN** the host SHALL reject the operation with a typed closed or cancelled result
- **AND** no platform, hardware, playback, publication, or persistence effect SHALL occur

#### Scenario: Lease is released repeatedly
- **WHEN** runtime cleanup and host teardown both attempt to release the same capability lease
- **THEN** the host SHALL perform terminal capability cleanup exactly once
- **AND** each release attempt SHALL complete without reacquiring the capability

### Requirement: Audio capabilities use high-level opaque handles
Any host audio capability made available to a channel SHALL represent work through semantic requests and opaque, lifecycle-bound handles. The host SHALL remain the sole owner of route selection, capture acquisition, ready and error beeps, recorder state, playback routing and ordering, and route release. Channels SHALL NOT inspect, select, retain, or release platform audio devices or routes.

#### Scenario: Channel requests host audio work
- **WHEN** a channel requests an authorized semantic audio operation
- **THEN** the host SHALL return a typed result or opaque operation handle
- **AND** the host SHALL choose and manage platform audio resources without revealing them to the channel

#### Scenario: Runtime schedules deferred playback
- **WHEN** a runtime schedules a playback operation from synthesized or captured audio via `talkcan.playback.schedule`
- **THEN** the scheduling operation SHALL NOT play audio through an ambient or process-wide output
- **AND** the playback capability SHALL accept the audio into the selection-aware deferred queue exactly once
- **AND** it SHALL return a successful scheduled result to Lua without returning any underlying queue token, route, or platform object

#### Scenario: PTT capture is prepared
- **WHEN** the active runtime accepts a channel-level input target
- **THEN** the host SHALL acquire and operate capture and routing through the existing host-owned audio lifecycle
- **AND** the runtime SHALL receive only high-level opaque channel audio handles and terminal events

#### Scenario: Opaque audio operation is cancelled
- **WHEN** the runtime generation closes or cancellation reaches an outstanding opaque audio operation
- **THEN** the host SHALL cancel or detach that operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT publish playback or another late effect

### Requirement: Text output transport is host owned
The host text-output capability SHALL accept channel text and logical profile information and SHALL own compilation to transport-specific output, Sleepwalker BLE/GATT/HID access, connection state, connection attempts, reconnect policy, serialization, and cleanup. A channel runtime SHALL NOT receive or control the Sleepwalker connection or its transport primitives. Each accepted submission SHALL complete exactly once as `Delivered`, `Rejected`, `Failed`, or `Indeterminate`; the host SHALL NOT automatically replay a terminal submission.

#### Scenario: Keyboard instance submits text
- **WHEN** a Keyboard runtime submits text and a logical host profile through its instance-scoped text-output capability
- **THEN** the host SHALL compile and transport the output using host-owned Sleepwalker policy
- **AND** it SHALL return exactly one terminal `Delivered`, `Rejected`, `Failed`, or `Indeterminate` outcome

#### Scenario: Text transport is disconnected
- **WHEN** an eligible runtime requests text output while the Sleepwalker transport is disconnected
- **THEN** the host capability SHALL apply the host-owned connection or reconnect policy and MAY expose a nonterminal pending state
- **AND** the runtime SHALL NOT initiate BLE discovery, GATT connection, HID transmission, or retry scheduling directly
- **AND** the submission SHALL eventually complete with one defined terminal outcome

#### Scenario: Text delivery reaches a terminal outcome
- **WHEN** a submission completes as `Delivered`, `Rejected`, `Failed`, or `Indeterminate`
- **THEN** the host SHALL NOT automatically replay that submission
- **AND** any later submission SHALL require an explicit new channel request

#### Scenario: Runtime closes during text delivery
- **WHEN** a Keyboard runtime closes while its text-output operation is pending
- **THEN** the host SHALL cancel or detach the instance operation and revoke its lease
- **AND** a later transport completion SHALL NOT update the closed runtime or report its text as newly delivered

### Requirement: Capability failures are normalized and isolated
The host SHALL translate platform exceptions and transport-specific failures at the capability boundary into typed semantic outcomes suitable for channel status and diagnostics. One instance's capability failure SHALL NOT expose sensitive platform details to channel code, terminate the capability service for unrelated instances, or invalidate unrelated runtime scopes.

#### Scenario: Platform operation fails
- **WHEN** a host capability encounters an Android, hardware, transport, or I/O failure
- **THEN** it SHALL return a typed semantic failure to the calling runtime
- **AND** detailed platform diagnostics SHALL remain host-owned
- **AND** unrelated runtime instances SHALL remain operational

#### Scenario: Capability diagnostics are emitted
- **WHEN** the host records diagnostics for acquisition, execution, cancellation, or cleanup
- **THEN** diagnostics SHALL identify the semantic capability, channel instance, operation phase, and normalized outcome
- **AND** they SHALL NOT expose audio payloads, channel text content, secrets, or hardware addresses

### Requirement: Persistent script state is exposed only through declared mounted storage and durable work
The host SHALL NOT provide ambient filesystem access, unrestricted paths, a general persistent key-value/database service, package-writable source tree, installer/verifier capability, or platform storage object. Arbitrary package documents SHALL remain available only through declared user-bound mounts. Separately, a package declaring `work.queue` and named queues MAY use the generic durable-work API solely for bounded opaque FIFO payloads, effect evidence/results, recovery epochs, and terminal tombstones; it SHALL expose no query language, arbitrary keys, files, history database, cross-instance sharing, or host storage object. Both facilities SHALL enforce declaration, ownership, bounds, lifecycle, typed outcomes, and built-in independence.

#### Scenario: Mounted storage is initialized
- **WHEN** the host initializes generic package storage
- **THEN** only declared bound mounts SHALL authorize arbitrary persistent package files
- **AND** no runtime SHALL gain ambient host/source filesystem access

#### Scenario: Durable queue is initialized
- **WHEN** a package declares `work.queue` and a bounded queue ID
- **THEN** the host MAY persist only values and evidence admitted through the exact work API
- **AND** Lua SHALL receive no database, path, transaction, or storage-provider object

#### Scenario: Built-in runtime operates normally
- **WHEN** a built-in Kotlin runtime uses existing capabilities
- **THEN** it SHALL function without a Lua actor, mount, package profile, or work Queue

### Requirement: Synthesis and playback are host-owned asynchronous effects
The host SHALL expose semantic synthesis and deferred audio playback capabilities that accept channel-domain text or opaque audio handles, channel instance identity, and runtime generation. Synthesis and playback SHALL be allowed to complete after the originating PTT session and SHALL be scheduled independently of the PTT audio route. The host SHALL own synthesis engines, PCM handling, output admission, playback routing, serialization, cancellation, pending state, and cleanup; runtimes SHALL receive only typed outcomes or opaque lifecycle-bound handles.

#### Scenario: Text is synthesized after PTT
- **WHEN** a runtime produces text for synthesis after its PTT target has released
- **THEN** the host SHALL synthesize and schedule playback through the semantic capabilities without reopening or retaining the PTT route
- **AND** the host SHALL associate the effect with the channel instance and runtime generation

#### Scenario: Admitted playback is temporarily ineligible for physical output
- **WHEN** a playback entry has already passed bounded queue admission but another channel is selected or current selection/route policy temporarily prevents physical playback
- **THEN** the host SHALL retain that authorized entry as pending for its addressed channel
- **AND** it SHALL NOT play through an ambient or unrelated channel output
- **AND** it SHALL re-evaluate physical admission only under the host's selection and audio policy while the entry and generation remain current

#### Scenario: Synthesis or playback is revoked
- **WHEN** the owning runtime generation is retired or the operation is cancelled before playback is admitted
- **THEN** the host SHALL cancel or detach the operation according to its semantic contract
- **AND** completion after cancellation SHALL NOT produce playback, publication, or another late effect

#### Scenario: Semantic operation exceeds deadline
- **WHEN** transcription, synthesis, or deferred-queue admission does not complete before its finite host-configured deadline
- **THEN** the host SHALL atomically terminate the operation with the language-neutral timeout outcome
- **AND** it SHALL cancel or detach backend work, clean operation-specific resources, and suppress every late completion or effect

#### Scenario: Deferred queue capacity is exhausted
- **WHEN** queue admission would exceed a finite per-instance, per-generation, or global entry-count or retained-audio-byte bound
- **THEN** the host SHALL reject admission with a typed busy outcome before consuming the caller's opaque audio handle
- **AND** it SHALL NOT create a partial queue entry, reroute existing audio, or evict another channel's entry implicitly

### Requirement: Asynchronous capability effects are generation-safe
Every asynchronous capability operation and queued playback entry SHALL carry the channel instance identifier and runtime generation that authorized it. The host SHALL accept completion, publication, playback, synthesis, and tool results only while that authorization remains current, SHALL revoke it on generation retirement or close, and SHALL make revocation and terminal completion idempotent. A late completion SHALL be observable only as a normalized stale or cancelled outcome and SHALL NOT mutate current channel state or produce deferred playback.

#### Scenario: Runtime generation is replaced
- **WHEN** a channel definition change or runtime replacement retires generation G and creates generation H
- **THEN** the host SHALL revoke outstanding capability effects and queued playback entries owned by G
- **AND** operations issued by H SHALL use separate authorization even when the channel instance ID is unchanged

#### Scenario: Late completion arrives
- **WHEN** a completion from a revoked generation arrives after replacement, shutdown, or restart
- **THEN** the host SHALL reject or ignore the completion as stale
- **AND** it SHALL NOT publish a response, play audio, execute a tool, or mark a current run complete

#### Scenario: Terminal completion is delivered twice
- **WHEN** the same asynchronous operation reports a terminal result more than once
- **THEN** the host SHALL commit at most one terminal effect and one durable terminal state
- **AND** repeated completion SHALL return a typed already-completed or stale result without replaying the effect

### Requirement: Capability boundaries remain usable by future language adapters
All agent, model-discovery, synthesis, playback, and tool capability contracts SHALL use language-neutral host-domain values, typed outcomes, opaque handles, and explicit lifecycle operations. They SHALL NOT depend on Kotlin implementation classes, Compose state, Android interfaces, OpenAI SDK types, or a Lua-specific ABI, and the host SHALL retain responsibility for adapting those contracts to any future language runtime.

#### Scenario: A non-Kotlin adapter invokes a capability
- **WHEN** a future language adapter invokes an authorized capability using the documented semantic values
- **THEN** the host SHALL apply the same validation, generation, ownership, and failure rules as for a built-in runtime
- **AND** the adapter SHALL NOT need access to SDK, Android, or transport objects

#### Scenario: SDK implementation changes
- **WHEN** the host replaces or upgrades its protocol SDK or synthesis engine
- **THEN** semantic capability inputs, outputs, and lifecycle rules SHALL remain unchanged
- **AND** persisted or projected channel-domain data SHALL NOT contain SDK implementation types

### Requirement: Host owns unified half-duplex audio admission
The host SHALL expose channel audio only through semantic capabilities and SHALL atomically serialize capture and channel-content playback before any physical route acquisition. Provider and runtime code SHALL NOT receive admission locks, `InputMode`, Android audio objects, routes, endpoints, PCM outputs, active playback handles, or device identities.

#### Scenario: Runtime schedules playback while capture owns audio
- **WHEN** a runtime schedules semantic playback while capture owns or reserves host audio
- **THEN** the capability SHALL return or retain a typed pending outcome
- **AND** no playback route object or admission primitive SHALL cross into the runtime

#### Scenario: Host admits delayed playback
- **WHEN** a pending semantic playback request is selected and host audio is free
- **THEN** the host SHALL own current-channel validation, current-mode resolution, route acquisition, PCM playback, interruption, cleanup, and typed outcome publication

### Requirement: Active playback is host-controllable
The host playback capability SHALL represent admitted playback as a lifecycle-bound host operation capable of exact completion, ducked rejection-tone overlay, explicit skip, route-failure interruption, and cleanup. Control operations SHALL affect the active output stream without exposing or reacquiring its route through a channel runtime.

#### Scenario: Rejection tone overlays active speech
- **WHEN** host PTT policy rejects a press during active playback
- **THEN** the active playback operation SHALL temporarily duck speech and mix one rejection tone into its existing output stream
- **AND** it SHALL restore speech and continue the same message without changing its durable lifecycle

#### Scenario: Host explicitly skips playback
- **WHEN** host control policy skips the active operation
- **THEN** the capability SHALL stop the output, complete cleanup, and report an explicit skipped outcome distinct from interruption or route failure

#### Scenario: Route failure interrupts playback
- **WHEN** the active route fails before playback completes
- **THEN** the capability SHALL stop and clean up the operation
- **AND** it SHALL report interruption or failure without reporting successful hearing or explicit skip

### Requirement: Input subsystem integration is boundary-only
The host audio capability SHALL treat the existing audio-input subsystem as an authoritative capture owner. It MAY request or observe a capture admission reservation and SHALL observe existing terminal-completion publication, but it SHALL NOT take over recorder setup, route gates, ready-beep ordering, committed target handling, terminal ownership, or exact-once input cleanup.

#### Scenario: Capture is admitted through host arbitration
- **WHEN** PTT is not blocked by playback and obtains host capture admission
- **THEN** the existing input subsystem SHALL execute its unchanged internal sequence
- **AND** the host arbitration layer SHALL release capture ownership only from the existing terminal-completion boundary

### Requirement: Plugin policy and operation mechanism remain separate
Plugin-owned policy (protocol selection, retry and backoff, polling cadence, state-machine, and routing decisions expressed in Lua) SHALL be distinct from the runtime operation mechanism that admits, suspends, resumes, cancels, and tears down work. A policy expressed in Lua SHALL NOT become a host-owned mechanism, and a host-owned operation mechanism SHALL NOT dictate plugin policy. The host SHALL remain authoritative for operation admission, generation authorization, capability lease revocation, and teardown sequencing; the plugin SHALL remain authoritative for the policy decisions that govern what work to attempt and how to respond to outcomes.

#### Scenario: Policy chooses retry behavior while mechanism owns cancellation
- **WHEN** a plugin's Lua policy decides to retry an operation after a failure
- **THEN** the decision to retry SHALL be expressed in plugin-owned policy
- **AND** the host-owned operation mechanism SHALL admit, suspend, resume, or cancel the resulting operation without dictating the retry decision

#### Scenario: Mechanism does not dictate plugin policy
- **WHEN** the host-owned operation mechanism admits or cancels an operation
- **THEN** it SHALL NOT prescribe polling cadence, protocol selection, backoff schedule, or state-machine transitions that the plugin owns
- **AND** the plugin SHALL retain authority over what work to attempt next

#### Scenario: Policy cannot bypass generation authorization
- **WHEN** a plugin policy schedules work that would outlive the admitting runtime generation
- **THEN** the host-owned mechanism SHALL cancel or drain that work on generation retirement
- **AND** the policy SHALL NOT retain authorization, capability leases, or effect delivery after the generation closes

### Requirement: Public runtime I/O is restricted to capability-mounted files in this change
This change SHALL expose the capability-gated `talkcan.fs` and `talkcan.audio` file operations defined by their public specs while retaining existing semantic audio modules. It SHALL NOT expose HTTP, sockets, general networking, arbitrary event-loop integration, unrestricted Lua `io`/`os`, a generic key-value/database API, package source mutation, installer, verifier, or binary-string file API. Filesystem operations SHALL require declared `storage.files` eligibility and a live bound mount; audio-file operations SHALL additionally require `audio.files`. Existing built-in capabilities SHALL remain usable without Lua.

#### Scenario: Revised runtime modules are initialized
- **WHEN** a revised Lua provider starts with declared file capabilities and a live mount
- **THEN** only the bounded documented storage/audio-file operations SHALL be available
- **AND** no ambient networking, raw platform filesystem, or package-management operation SHALL appear

#### Scenario: Built-in runtime does not require public modules
- **WHEN** `builtin:journal` or another built-in runtime operates
- **THEN** it SHALL retain its current host capability path without constructing a Lua state
- **AND** the generic file APIs SHALL not replace or intercept its behavior

### Requirement: Mounted-storage capability uses language-neutral requests
The host SHALL expose generic operations for mount lookup/status, directory creation, stat, paginated listing, bounded UTF-8 read/write, and nonrecursive removal using host-domain mount identity, logical relative paths, bounded options, and typed outcomes. Contracts SHALL not contain Android URI/path/document/provider classes, Kotlin coroutine/UI types, iOS URL/bookmark types, file descriptors, or Journal values. Every operation SHALL be associated with instance and generation authorization and a revocable lease/binding.

#### Scenario: Lua adapter requests directory listing
- **WHEN** the language adapter submits a valid bounded listing request
- **THEN** the capability SHALL return a portable page or typed failure
- **AND** adapter code SHALL receive no platform provider object

### Requirement: Audio-file capability composes recording and mounted-storage authority
The host SHALL expose generic Recording describe/open/export operations using opaque Recording handles, mount authority, logical paths, exact format tokens, and typed outcomes. It SHALL own WAV decoding/writing, OGG/Vorbis encoding, app-private staging, provider streaming, quotas, cancellation, and cleanup. The capability SHALL not contain Journal entry, metadata, output-mode, Markdown, recovery, or path-layout logic.

#### Scenario: Package exports OGG to mount
- **WHEN** an authorized adapter requests OGG/Vorbis export of a live Recording
- **THEN** the capability SHALL encode and publish through the mounted-storage boundary or return one typed failure
- **AND** it SHALL not infer why the package requested the file

### Requirement: Public file capabilities remain portable to future language and platform adapters
Mounted-storage and audio-file ports SHALL use portable host-domain values, opaque handles, explicit lifecycle, and normalized outcomes. Android implementation SHALL use persisted SAF authority behind the port. A future non-Android adapter SHALL be able to implement the same contracts without Android classes or changes to package source. Platform-specific grant acquisition/release and document coordination SHALL remain adapter-owned.

#### Scenario: Future platform implements tree backend
- **WHEN** another platform maps a user-selected document tree to the host mount contract
- **THEN** existing Lua packages SHALL continue using the same mount IDs, relative paths, operations, results, and errors

### Requirement: Lua keyboard output adapts the existing host-owned semantic facility
The host SHALL expose public `keyboard.output` through an instance- and generation-scoped semantic keyboard-output capability. The adapter SHALL accept only bounded text or supported semantic keys plus a logical profile ID and SHALL own current-profile validation, keymap compilation, shared admission, Sleepwalker discovery and preparation, BLE/GATT/HID transport, acknowledgement, cancellation, timeout, serialization, force release, disarm, and cleanup. Lua runtimes SHALL NOT receive or control any underlying profile object, keymap, connection, transport, hardware address, frame, acknowledgement token, or Android object.

#### Scenario: Lua submits semantic keyboard text
- **WHEN** an authorized Lua owner submits text and a logical profile through `keyboard.output`
- **THEN** the host adapter SHALL revalidate the profile and perform compilation and transport internally
- **AND** Lua SHALL observe only a normalized typed terminal result

#### Scenario: Stable profile ID normalizes imported metadata casing
- **WHEN** a public lowercase profile ID selects a keymap whose imported platform or layout metadata retains different casing
- **THEN** the host SHALL resolve the canonical current database profile by its stable key before compilation
- **AND** it SHALL NOT reject the profile because object equality preserves the imported casing

#### Scenario: Built-in and Lua instances share transport
- **WHEN** built-in and Lua runtimes require keyboard output concurrently
- **THEN** the host SHALL serialize them through one shared transport policy
- **AND** neither runtime SHALL start a duplicate connection, own transport lifetime, or close a sibling's resource

### Requirement: Host capability preparation is generic and readiness directed
The host SHALL maintain a bounded registry mapping public capability identifiers to optional host-owned preparers. A readiness projection MAY request preparation only for a capability declared by its package and registered as preparable. Preparation SHALL be bound to the input attempt, instance, and current generation and SHALL own joining, serialization, deadline, cancellation, cleanup, and stale-completion handling. Capability declaration alone SHALL NOT invoke preparation, and Lua SHALL NOT receive a preparation callable or transport object.

#### Scenario: Readiness requests registered keyboard preparation
- **WHEN** a live runtime's cached readiness requests declared recoverable `keyboard.output`
- **THEN** the host SHALL invoke its registered bounded preparer once or join compatible in-flight preparation
- **AND** it SHALL not expose Sleepwalker scan, GATT, or connection state to Lua

#### Scenario: Readiness requests undeclared or non-preparable capability
- **WHEN** readiness names a capability that is undeclared or lacks a registered preparer
- **THEN** the host SHALL invalidate that readiness result before preparation
- **AND** it SHALL perform no capability or platform effect

#### Scenario: Input attempt ends during preparation
- **WHEN** release, cancellation, replacement, or shutdown terminates the input attempt while preparation is pending
- **THEN** that attempt SHALL remain terminal and uncommitted
- **AND** a later preparation completion SHALL not accept input, play a ready beep, or start capture
- **AND** shared connection retention or cleanup SHALL remain host-owned

### Requirement: Keyboard-output authority is generation scoped and selection independent
A declared keyboard-output lease and every operation derived from it SHALL be authorized by the live channel instance, runtime generation, and execution owner rather than current active selection. Deselecting an instance SHALL NOT revoke admitted or later managed-task output from its live generation. Disablement, configuration or revision replacement, removal, runtime failure/close, and service shutdown SHALL revoke the affected generation monotonically and SHALL not affect siblings.

#### Scenario: Live task outputs while unselected
- **WHEN** a live generation-owned task requests keyboard output while another channel is selected
- **THEN** the host SHALL evaluate the operation under the original generation's capability and policy
- **AND** it SHALL not redirect attribution, profile, outcome, or cleanup to the selected instance

#### Scenario: Generation is revoked with queued operations
- **WHEN** a generation is replaced or removed while its output is queued
- **THEN** the host SHALL reject queued not-yet-effective operations and revoke their leases idempotently
- **AND** it SHALL preserve unrelated instances and the shared adapter

### Requirement: Generic HTTP remains transport-level rather than provider-level
The host SHALL expose HTTPS as a bounded semantic transport capability with normalized method, URL, headers, body, and response values. It SHALL own platform networking and TLS but SHALL not construct OpenAI/provider requests, inject provider credentials implicitly, interpret JSON/provider errors, discover models, iterate tools, or retain conversations. Existing host-owned OpenAI completion/model-discovery capabilities SHALL remain available only to the retained built-in and SHALL not be fallback paths for external packages.

#### Scenario: Lua package submits Chat Completion
- **WHEN** package Lua constructs an OpenAI-compatible request and calls generic HTTP
- **THEN** the host SHALL process it as HTTPS bytes/metadata under generic policy
- **AND** no OpenAI Kotlin contract SHALL participate in that request

### Requirement: Profile and secret capabilities are repository, selection, and generation scoped
The host SHALL expose generic profile lookup and protected secret resolution only through package-owned type identity, selected profile grant, declared capability, current profile revision, execution owner, and live generation/resolver scope. Profile lookup SHALL return detached scalar data and opaque secret references; secret resolution MAY return plaintext only under explicit grant. These capabilities SHALL not expose another package's profiles, protected aliases, credential-store objects, SDK clients, or ambient enumeration.

#### Scenario: Runtime selects one profile
- **WHEN** an authorized instance resolves its configured same-package profile
- **THEN** it SHALL receive only detached allowed fields and secret references
- **AND** sibling profile access SHALL remain denied

### Requirement: Durable work capabilities are semantic effect ledgers, not policy engines
The host SHALL own durable submission, FIFO sequence, claims, effect-start/result evidence, safe restart classification, epochs, quotas, purge, and projections through generic values. It SHALL not interpret payload schema, model messages, tool calls, protocol response, retry safety beyond committed effect evidence, conversation context, or package terminal meaning. Lua SHALL choose stable effect keys and application behavior; the host SHALL enforce non-replay when evidence is ambiguous.

#### Scenario: Work effect contains OpenAI response
- **WHEN** Lua commits a normalized provider result under an effect key
- **THEN** the host SHALL persist it as opaque bounded data until terminal purge
- **AND** no host adapter SHALL parse it as an OpenAI response

### Requirement: New capabilities remain explicitly acquired and independently revocable
Network, profile, secret, and work eligibility SHALL compile into the same instance/generation-scoped acquisition and lease model as existing capabilities, with additional repository/profile/queue/effect ownership where applicable. Revoking one authority SHALL not silently grant another or terminate unrelated package instances. Package removal, profile revision, configuration replacement, generation close, and service shutdown SHALL revoke affected leases and complete/cancel/indeterminate operations under their exact effect contracts.

#### Scenario: Secret authority is revoked during request preparation
- **WHEN** a profile revision invalidates the predecessor secret grant before HTTP effect starts
- **THEN** secret resolution/request preparation SHALL fail without remote effect
- **AND** unrelated profiles and queues SHALL remain usable

