## Purpose

Defines the channel framework, including active selection, configuration persistence, generic lifecycle/input event consumption, durable background operation scheduling, serialization of channel turns, and language-neutral interfaces.

## Requirements

### Requirement: Active channel selection
The system SHALL maintain exactly one active channel instance at any time, identified by a stable ID present in the persisted ordered channel catalogue. The active channel instance is the intended destination for PTT audio captures, subject to its runtime readiness evaluation. The migrated default catalogue SHALL include provider-backed Journal, Debug, and Keyboard instances, while the runtime SHALL support catalogue additions, removals, and multiple instances referencing the same implementation provider.

#### Scenario: One channel instance is active
- **WHEN** a channel instance is selected as active
- **THEN** PTT captures SHALL be evaluated against that instance's runtime readiness state
- **AND** every other catalogue instance SHALL be inactive

#### Scenario: Multiple instances share a provider
- **WHEN** the catalogue contains two instances referencing the same channel implementation provider
- **THEN** either instance SHALL be independently selectable by its stable instance ID
- **AND** selection SHALL NOT be inferred from provider identity or implementation type

### Requirement: Channel configuration persistence
The system SHALL persist the ordered provider-backed channel definitions across app restarts, including each definition's stable instance ID, stable provider reference, display name, enabled state, versioned opaque configuration payload, and list position. Configuration changes SHALL be addressed by stable instance ID, validated and migrated by the referenced provider, and take effect for subsequent PTT preparation without requiring a service restart. A configuration change SHALL NOT alter the target already committed to an active PTT session.

#### Scenario: App restarted after instance configuration
- **WHEN** the user configures a channel instance and the app is killed and restarted
- **THEN** that instance's ID, provider reference, name, position, enabled state, configuration schema version, and complete opaque payload SHALL be restored

#### Scenario: Provider configuration changed at runtime
- **WHEN** the user changes a channel instance's valid provider configuration while the service is running
- **THEN** the new configuration SHALL take effect for the next PTT preparation for that instance
- **AND** the current committed target, if any, SHALL retain its accepted configuration snapshot

#### Scenario: Configuration action targets one instance
- **WHEN** a provider-rendered editor changes a definition while another instance references the same provider
- **THEN** the action SHALL retain the editor's instance ID through navigation and persistence
- **AND** it SHALL NOT read or mutate a same-provider definition selected by list position, implementation identity, or a legacy singleton ID

#### Scenario: Unknown payload fields survive persistence
- **WHEN** a valid provider configuration payload contains fields the current host does not interpret
- **THEN** the host SHALL persist and restore those fields without loss
- **AND** the host SHALL NOT project the payload through a closed built-in configuration type

### Requirement: Channels consume audio input events
Channels SHALL consume PTT input through generic channel-level session events and audio supplied as high-level opaque handles by host-owned capabilities. Before the host plays the ready beep, a channel runtime SHALL expose whether it can accept the candidate input and, if accepted, return a committed target. Channels SHALL NOT receive `ResolvedAudioRoute`, `ScoRoute`, `PcmOutput`, `CaptureSource`, `InputMode`, Android audio route objects, Bluetooth HFP state, Telecom endpoint state, route-gate status, recorder diagnostic objects, or other platform transport objects.

#### Scenario: Active channel accepts input session
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the runtime is configured, initialized, and able to process this input
- **THEN** the runtime SHALL provide an accepted committed channel input target to the host audio input subsystem
- **AND** the target SHALL consume generic session events and audio through opaque host-owned handles
- **AND** the channel SHALL NOT receive the route-readiness or platform facts used before commitment

#### Scenario: Active channel refuses input before ready beep
- **WHEN** PTT capture is being prepared for the active channel
- **AND** the runtime is missing required configuration, unavailable, uninitialized, or unable to create its input target
- **THEN** the runtime SHALL report a typed refusal to the host audio input subsystem
- **AND** the audio input subsystem SHALL NOT play the ready beep for that PTT
- **AND** route cleanup SHALL remain owned by the audio input subsystem

#### Scenario: Channel does not own input route cleanup
- **WHEN** PTT capture ends, fails, times out, or is cancelled
- **THEN** the committed channel target SHALL handle only its channel-domain terminal event and status
- **AND** the audio input subsystem SHALL release route, capture, and playback resources
- **AND** the channel SHALL NOT release SCO, Telecom, recorder, or local route resources directly

#### Scenario: Channel switch during active session
- **WHEN** the active channel selection changes while an audio input session is active
- **THEN** the session SHALL remain associated with the channel target accepted at session commitment until the session terminates
- **AND** changing channel selection SHALL NOT leak or release the active audio route outside the audio input subsystem

#### Scenario: Route or commitment failure is reported without route internals
- **WHEN** an input session fails because platform route state, recorder state, capability acquisition, or channel acceptance cannot prove a committed input path
- **THEN** the selected channel SHALL receive only a generic cancellation or failure event if a committed channel target exists
- **AND** the channel SHALL NOT receive Android route objects, transport state, route-gate internals, or detailed host diagnostics


### Requirement: Channel runtimes depend only on generic events and host capabilities
A channel implementation SHALL express behavior through provider-validated configuration, generic lifecycle and input events, and semantic host capability interfaces. Host capabilities SHALL expose opaque high-level handles or domain results and SHALL retain ownership of Android resources, hardware transports, connection and reconnection policy, concurrency, cleanup, and detailed diagnostics. Core channel dispatch SHALL NOT branch on built-in implementation identity to deliver events or capabilities.

#### Scenario: Runtime requests a semantic host effect
- **WHEN** a runtime needs text output, audio playback, or another host-owned effect
- **THEN** it SHALL request the operation through the corresponding semantic host capability
- **AND** it SHALL NOT receive or control the underlying Android, Bluetooth, Telecom, recorder, filesystem-transport, or connection object

#### Scenario: Generic event reaches different providers
- **WHEN** two runtimes from different providers accept the same generic channel input event
- **THEN** the host SHALL dispatch that event through the same provider-neutral runtime contract
- **AND** core dispatch SHALL NOT inspect provider identity or a built-in channel kind

#### Scenario: Host capability is unavailable
- **WHEN** a runtime requests a semantic capability that the host cannot currently provide
- **THEN** the capability SHALL return a generic typed unavailable or failed result
- **AND** the runtime SHALL NOT be given platform-specific state or permission to implement host connection or retry policy

### Requirement: Durable channel operations outlive PTT terminal callbacks
The channel framework SHALL provide an explicit host-owned durable-operation boundary through which a runtime can enqueue a channel-domain user turn or other accepted work before its transient PTT target terminates. Completion of the PTT target and release of its audio route SHALL NOT cancel accepted durable work. Durable work SHALL be identified by channel instance, runtime generation, and run identity and SHALL NOT retain callbacks, recorder state, route leases, or transient audio handles.

#### Scenario: User turn is accepted before terminal callback
- **WHEN** a channel runtime submits a user turn while handling a committed PTT input and the host accepts it
- **THEN** the framework SHALL record or enqueue the turn as durable channel work
- **AND** the PTT terminal callback SHALL be permitted to complete and release all transient input resources
- **AND** processing SHALL continue through host-owned operations after that callback returns

#### Scenario: PTT callback is cancelled after enqueue
- **WHEN** the originating PTT callback is cancelled, times out, or reports a terminal failure after durable work was accepted
- **THEN** the accepted durable work SHALL retain its channel instance and run identity
- **AND** the framework SHALL NOT restart capture, retain the route, or invoke the cancelled callback again

#### Scenario: Channel selection changes after enqueue
- **WHEN** the active channel changes after a user turn has been enqueued
- **THEN** the durable work SHALL remain addressed to its original channel instance
- **AND** it SHALL NOT be redirected to the newly selected channel or use the new channel's runtime implicitly

### Requirement: Durable channel work is serialized per channel instance
The framework SHALL serialize accepted user turns and their response-processing stages for each channel instance in deterministic queue order. A queued turn SHALL NOT begin a conflicting remote run, tool loop, synthesis operation, or response publication concurrently with an earlier turn for that same channel unless the durable operation contract explicitly permits that stage. Queues for different channel instances SHALL remain independently schedulable.

#### Scenario: Multiple turns arrive for one channel
- **WHEN** several released PTT recordings produce accepted turns for one channel instance
- **THEN** the framework SHALL persist their queue order and process them one at a time through the channel's serialized conversation boundary
- **AND** a later turn SHALL remain queued rather than racing the earlier turn

#### Scenario: Turns target different channels
- **WHEN** accepted turns are queued for two different channel instances
- **THEN** each channel's queue SHALL retain its own order and state
- **AND** work for one channel SHALL NOT mutate, reorder, or consume the other channel's queue

### Requirement: Durable responses publish independently of the originating input session
The framework SHALL allow a host-owned channel operation to publish an inbound response, processing state, pending state, or normalized failure after the originating input session has terminated. Publication SHALL use semantic channel projections and asynchronous synthesis/playback capabilities rather than PTT terminal return values or ambient output. Selection, playback admission, heard state, and delayed delivery SHALL remain host-owned policy.

#### Scenario: Response arrives while channel is selected
- **WHEN** a durable response completes for the currently selected channel and host audio playback is admissible
- **THEN** the framework SHALL publish the response and SHALL permit host-owned synthesis and playback without a new PTT session
- **AND** the runtime SHALL NOT access the audio route or output device

#### Scenario: Response arrives while channel is not selected
- **WHEN** a durable response completes for a channel that is not currently selected or whose output is busy
- **THEN** the framework SHALL publish a pending response state for that channel
- **AND** playback SHALL be deferred until the host selection and admission policy permits it
- **AND** returning to the channel SHALL NOT require replaying the original PTT callback

#### Scenario: Response publication is cancelled
- **WHEN** the operation generation is revoked before a response is published or played
- **THEN** the framework SHALL suppress the stale publication and playback effect
- **AND** it SHALL retain only the normalized durable ledger outcome permitted by the run contract

### Requirement: Runtime callbacks do not own asynchronous orchestration
A channel runtime SHALL request durable work, transcription, remote completion, tool execution, synthesis, and playback only through explicit semantic host capabilities. Host-invoked runtime callbacks SHALL remain bounded: they SHALL NOT create process-global scopes, launch untracked threads, own unmanaged network clients, retain PTT callbacks, or implement playback routing or transport cleanup. A conforming host-owned actor MAY own generation-scoped background tasks whose Lua policy controls polling, protocol, retry, and backoff behavior, provided every such task is admitted, tracked, cancelled, and torn down within the actor's runtime generation and does not retain PTT callbacks, recorder state, route leases, or transient audio handles. The host SHALL expose typed acceptance, progress, terminal, cancelled, failed, and indeterminate outcomes and SHALL remain authoritative for Android resource ownership, durable record persistence, playback admission, and platform cleanup.

#### Scenario: Runtime requests asynchronous work
- **WHEN** a runtime needs to continue channel processing after an input event
- **THEN** it SHALL submit a semantic operation with explicit channel and generation identity
- **AND** the host SHALL own scheduling, durable state, cancellation, and effect delivery for that operation
- **AND** no ambient coroutine, thread, SDK client, or platform resource SHALL be injected into the runtime

#### Scenario: Host operation fails
- **WHEN** a host-owned asynchronous stage fails
- **THEN** the framework SHALL deliver a normalized semantic failure associated with the run identity
- **AND** unrelated channel instances and their queues SHALL remain operational

#### Scenario: Host-invoked callback stays bounded
- **WHEN** the host invokes a runtime callback to deliver an event, operation completion, or lifecycle transition
- **THEN** the callback SHALL NOT create a process-global scope, launch an untracked thread, or retain a PTT callback
- **AND** any work that cannot complete within the bounded callback SHALL be deferred to a generation-scoped actor task or a semantic host operation

#### Scenario: Actor owns generation-scoped background work
- **WHEN** a host-owned actor schedules a background task whose Lua policy controls polling, protocol, or retry behavior
- **THEN** the task SHALL be admitted and tracked within the actor's runtime generation
- **AND** the host SHALL cancel or drain the task on generation retirement and SHALL NOT permit it to outlive that generation
- **AND** the task SHALL NOT retain PTT callbacks, recorder state, route leases, or transient audio handles

### Requirement: Durable framework contracts are language-neutral
The framework SHALL define durable operation requests, lifecycle events, run identities, channel projections, and typed outcomes using language-neutral host-domain values and opaque handles. It SHALL NOT expose Kotlin classes, Android callbacks, Compose state, OpenAI SDK types, audio-route objects, or transport objects, and it SHALL remain implementable by built-in Kotlin providers without introducing a Lua engine or script-runtime ABI.

#### Scenario: Future language adapter uses the framework
- **WHEN** a future language adapter submits a valid durable operation and consumes its semantic events
- **THEN** the host SHALL enforce the same queue, generation, cancellation, publication, and failure rules
- **AND** the adapter SHALL NOT require access to Kotlin, Android, SDK, or transport implementation types

#### Scenario: Platform implementation changes
- **WHEN** the host replaces its SDK, transcription engine, synthesis engine, or playback implementation
- **THEN** durable channel operation contracts and persisted channel-domain envelopes SHALL remain stable
- **AND** no platform implementation object SHALL become part of a provider or runtime contract

### Requirement: Channel selection routes PTT input independently of background execution
Selection of the active channel instance SHALL be the routing decision for new user-driven PTT input. An enabled channel instance that is not the active PTT destination SHALL be permitted to perform independent background work through its host-owned actor while the foreground service is alive, subject to its runtime generation, capability leases, and resource policy. Selection SHALL NOT gate background execution by other enabled instances, and background execution SHALL NOT redirect, preempt, or capture PTT input addressed to the active channel.

#### Scenario: Unselected enabled instance continues background work
- **WHEN** an enabled channel instance is not the active PTT destination and its actor is live
- **THEN** that instance SHALL be permitted to continue independent background work within its generation
- **AND** that work SHALL NOT be suspended merely because the instance is not selected for PTT

#### Scenario: Background execution does not capture PTT input
- **WHEN** a background task of a non-active instance is running and PTT input is prepared
- **THEN** the PTT input SHALL be routed to the active channel instance only
- **AND** the background task SHALL NOT redirect, intercept, or consume that input

#### Scenario: Selection change does not cancel unrelated background work
- **WHEN** the active channel selection changes from instance A to instance B
- **THEN** background work owned by a third enabled instance C SHALL remain within its own generation and queue
- **AND** only the retirement of instance C's generation SHALL cancel or drain that work

### Requirement: Actor work remains generation-scoped
Every actor task, suspended coroutine, outstanding operation, and retained handle SHALL remain scoped to the runtime generation that admitted it. Generation retirement, runtime replacement, or shutdown SHALL cancel or drain all descendant work, SHALL revoke its capability leases, and SHALL prevent late effects from mutating current channel state. No actor work SHALL escape its generation to become process-global, outlive replacement, or retain authorization after the generation closes.

#### Scenario: Generation retirement drains descendant work
- **WHEN** a runtime generation is retired by replacement or shutdown
- **THEN** the host SHALL cancel or drain every background task, suspended coroutine, and outstanding operation owned by that generation
- **AND** no such work SHALL remain schedulable after the generation closes

#### Scenario: Late effect from a retired generation is suppressed
- **WHEN** a late completion or effect from a retired generation arrives after replacement or shutdown
- **THEN** the host SHALL reject or ignore it as stale
- **AND** it SHALL NOT publish a response, play audio, execute a tool, or mutate current channel state

#### Scenario: Actor work does not become process-global
- **WHEN** an actor schedules a long-lived polling, subscription, or retry task
- **THEN** that task SHALL be tracked within the admitting generation and SHALL NOT create a process-global scope, untracked thread, or ambient timer that outlives the generation
- **AND** closing the generation SHALL be sufficient to terminate the task
