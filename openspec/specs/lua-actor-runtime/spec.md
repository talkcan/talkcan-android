## Purpose

TBD. Defines the production-internal Lua actor runtime boundary, promoting the embedded Lua 5.4 kernel from the proof substrate into a production-facing runtime with bounded serialized mailboxes, cooperative coroutine scheduling, host-owned lifecycle, failure containment, instruction/memory constraints, and replacement/closure mechanisms.
## Requirements
### Requirement: The selected Lua kernel is promoted cleanly from the proof substrate
The system SHALL promote the selected Kotlin-owned Lua 5.4.8 actor kernel — which embeds pinned prebuilt third-party natives through a vendored AAR binding — with its normalized outcome mapping, protected execution, message-passing coroutine continuation, instruction interruption, and conformance coverage from the substrate proof into one production-internal runtime boundary. The change SHALL remove the superseded first-party Rust cdylib kernel, its JNI exports, the Kotlin JNI bridge layer, the Gradle native-build wiring for that crate, proof-only harness paths, and experimental feature flags rather than introducing a second parallel kernel. The retained kernel SHALL execute Lua source chunks and SHALL NOT expose plugin-provided bytecode loading, `package.loadlib`, C-module searchers, JNI, FFI, or plugin-provided shared-library loading.

#### Scenario: Proof harness is removed
- **WHEN** the change completes and the production runtime is built
- **THEN** the repository SHALL retain exactly one internal Lua kernel and its conformance coverage
- **AND** it SHALL NOT contain an active proof bridge, a superseded native kernel crate, candidate-only engine, instrumentation-only composition, or dormant alternative implementation

#### Scenario: Binary or native module loading is requested
- **WHEN** a Lua chunk attempts to load binary bytecode or dynamically load a native module
- **THEN** the runtime SHALL reject the operation without executing the supplied binary or shared library
- **AND** the owning actor SHALL remain usable
### Requirement: One Lua actor owns one state per runtime generation
Each channel runtime generation SHALL own exactly one production-internal Lua actor and exactly one independent Lua state, confined to its own dedicated engine thread. The actor SHALL own its globals, module caches, coroutines, mailbox, suspended operations, and background-task scopes without observable sharing across generations or instances. Two actors of the same provider SHALL observe only their own state, and closing one actor SHALL NOT change the surviving actor's values or usability.

#### Scenario: Two actors mutate equal global names
- **WHEN** two live actors assign different values to the same Lua global and load package-local modules
- **THEN** each actor SHALL observe only its own value and module cache
- **AND** closing either actor SHALL NOT change the surviving actor's values or usability

#### Scenario: Handle belongs to another generation
- **WHEN** a host completion or control request submits a coroutine, task, or operation handle to an actor whose generation does not own it
- **THEN** the runtime SHALL return a typed invalid-owner or stale outcome
- **AND** it SHALL NOT enter or mutate either Lua state
### Requirement: The actor mailbox is bounded and serialized
Each actor SHALL receive events through one bounded event mailbox. The mailbox SHALL admit events in deterministic FIFO order and SHALL reject new events with a typed busy result when no capacity remains rather than allocating an unbounded waiter queue. An admitted event SHALL begin its first Lua entry only after earlier ready work has yielded or completed; after an earlier event yields, later admitted events MAY begin before that earlier event reaches its terminal result. Mailbox admission, ready-queue scheduling, and operation completion SHALL remain serialized so that at most one Lua entry executes for one actor at any time. Events for different actors MAY execute independently.

#### Scenario: Mailbox is saturated
- **WHEN** an actor's mailbox cannot admit another event
- **THEN** the runtime SHALL reject the event with a typed busy result
- **AND** it SHALL NOT allocate an unbounded waiter or start the rejected event later

#### Scenario: Concurrent events target one actor
- **WHEN** two events are admitted concurrently for the same actor
- **THEN** their first Lua entries SHALL begin in deterministic admission order without overlap
- **AND** if the earlier event yields, the later event MAY begin before the earlier event reaches its terminal result
- **AND** at most one event or continuation SHALL execute Lua at any instant

#### Scenario: Events target different actors
- **WHEN** independent events are admitted for different live actors
- **THEN** serialization of either actor SHALL NOT require execution under the other actor's mailbox or lock
- **AND** the actors MAY execute concurrently

### Requirement: Cooperative coroutine scheduling allows multiple suspensions under a single native entry
The actor SHALL allow multiple logical coroutines to suspend independently and resume through generation-safe opaque operation completions while at most one Lua entry executes at any time. A coroutine that yields a host-operation token SHALL suspend inside a blocking round-trip on the actor's dedicated engine thread: the engine thread posts a typed host-operation request and blocks awaiting the normalized completion, which is delivered into the owning coroutine exactly once. The engine thread SHALL NOT execute Lua instructions during the suspension interval. The actor SHALL resume the owning coroutine exactly once when the operation completes, and SHALL NOT permit a second coroutine to enter Lua while another coroutine is entered.

Managed background tasks admitted via `talkcan.runtime.spawn` MAY remain live until their function returns, fails, or the owning generation is closed. The host SHALL NOT terminate a task solely because a generic wall-clock lifetime has elapsed. The host SHALL bound the maximum number of concurrently managed tasks admitted per generation; exhaustion SHALL return `E_BUSY`. Suspended time — periods during which a background coroutine is suspended in `sleep` or awaiting a host operation — SHALL NOT be charged against active Lua execution limits; only active Lua execution slices remain subject to host-configured instruction-count and wall-clock bounds.

Each `talkcan.runtime.sleep` call SHALL establish an operation-specific deadline computed as `requested_delay + bounded_slack`, where `bounded_slack` is a host-configured timer margin. If timer completion wins before that deadline, the host SHALL resume the coroutine with `(true, nil)`. If the deadline wins before timer completion, the host SHALL resume the coroutine exactly once with `(nil, {error = "E_TIMEOUT"})`; a later timer completion SHALL be rejected as stale and SHALL NOT resume Lua again. On generation close, the sleeping coroutine SHALL NOT be resumed and neither timeout, cancellation, nor any outcome SHALL be delivered.

#### Scenario: Multiple coroutines suspend independently
- **WHEN** several coroutines of one actor yield host-operation tokens concurrently
- **THEN** each suspended coroutine SHALL execute no Lua instructions while its operation is pending
- **AND** at most one coroutine SHALL be entered at any time
- **AND** each coroutine SHALL resume exactly once when its owning operation completes

#### Scenario: Coroutine resumes while another is entered
- **WHEN** an operation completes for a suspended coroutine while another coroutine of the same actor is entered
- **THEN** the runtime SHALL queue the resume and SHALL NOT enter the second coroutine concurrently
- **AND** the queued resume SHALL execute once the entered coroutine yields or completes

#### Scenario: Background task loops with periodic sleep beyond a former generic task deadline
- **WHEN** a spawned background task repeatedly performs brief work and calls `talkcan.runtime.sleep`, and its cumulative wall-clock lifetime exceeds the generic deadline used by the pre-change actor policy
- **THEN** the runtime SHALL continue to resume the task across sleep boundaries because each sleep establishes a new operation-specific deadline
- **AND** the runtime SHALL NOT terminate the task or fail a sleep solely because cumulative task wall time exceeds that former generic deadline
- **AND** the task MAY remain live as long as each active slice satisfies execution limits, each sleep completes within its operation-specific deadline, and the generation remains open

#### Scenario: Long sleep uses its own operation-specific deadline
- **WHEN** a spawned background task requests a sleep longer than the generic operation deadline used by the pre-change actor policy and timer completion wins before the sleep's own `requested_delay + bounded_slack` deadline
- **THEN** the runtime SHALL resume the coroutine with `(true, nil)`
- **AND** the runtime SHALL NOT fail the sleep solely because elapsed wall time exceeded that former generic deadline

#### Scenario: Sleep deadline wins before timer completion
- **WHEN** a spawned background task calls `talkcan.runtime.sleep` and its operation-specific deadline passes before timer completion
- **THEN** the host SHALL classify the operation as timed out
- **AND** it SHALL resume the sleeping coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_TIMEOUT"})`
- **AND** a later timer completion SHALL be rejected as stale without resuming Lua again
- **AND** the coroutine SHALL remain live and available for further host operations

#### Scenario: Infinite active work is interrupted by instruction policy
- **WHEN** a spawned background task enters an infinite pure-Lua loop without yielding
- **THEN** the runtime SHALL interrupt execution within a finite instruction-count bound
- **AND** the actor SHALL close without relying on cooperative Lua return
- **AND** the interruption SHALL NOT depend on task lifetime, accumulated wall time, or a generic operation timeout

#### Scenario: Sleeping coroutine is cleaned up on generation close
- **WHEN** a spawned background task is suspended in `talkcan.runtime.sleep` and the owning actor generation is closed or replaced
- **THEN** the host SHALL NOT resume the sleeping coroutine
- **AND** it SHALL NOT deliver a timeout, cancellation, or any outcome to the coroutine
- **AND** the generation's operation tokens SHALL be invalidated and the Lua state closed

#### Scenario: Task admission limit is exhausted
- **WHEN** a callback calls `talkcan.runtime.spawn` and the per-generation maximum number of concurrently managed background tasks is already reached
- **THEN** the spawn call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the function SHALL NOT be executed or queued
### Requirement: Task and operation identity is opaque and generation-safe
The host and runtime SHALL represent coroutines, background tasks, and suspended operations by opaque identifiers validated against their owning actor generation. The system SHALL NOT expose raw `lua_State*` pointers, coroutine references, registry indexes, or native allocation addresses to host code. A completion, cancellation, or close request bearing a stale, foreign, or closed identifier SHALL return a typed terminal outcome without entering Lua or producing another native effect.

#### Scenario: Stale handle is submitted after replacement
- **WHEN** a completion arrives for an operation owned by a replaced generation
- **THEN** the runtime SHALL return a typed stale outcome
- **AND** it SHALL NOT enter the current generation's Lua state

#### Scenario: Foreign handle is submitted
- **WHEN** a completion arrives for an operation owned by another actor
- **THEN** the runtime SHALL return a typed invalid-owner outcome
- **AND** it SHALL NOT enter or mutate either actor's Lua state

### Requirement: Terminal admission is exactly-once
Each admitted operation and background task SHALL accept at most one terminal completion, cancellation, or close outcome. Duplicate, cancelled, closed, foreign-state, and stale-generation terminal requests SHALL return typed terminal outcomes without resuming Lua or producing another native effect. A terminal outcome that wins a race against a competing terminal request SHALL be the sole effect delivered to the owning coroutine.

#### Scenario: Completion is delivered twice
- **WHEN** the host submits two terminal completions for the same operation
- **THEN** at most one completion SHALL resume Lua
- **AND** the duplicate SHALL return an already-completed or stale outcome without another Lua effect

#### Scenario: Completion races cancellation
- **WHEN** cancellation and completion race for one suspended operation
- **THEN** exactly one terminal outcome SHALL win
- **AND** Lua SHALL be resumed at most once
- **AND** the losing request SHALL observe a cancelled, completed, or stale terminal result

### Requirement: Actor startup and readiness are host-owned
The host SHALL own actor startup, readiness projection, and the readiness latch. Before readiness, an actor MAY create its state, load source, validate its entrypoint, and execute the bounded protected startup entry authorized by the host, but it SHALL NOT admit ordinary mailbox events or start plugin background work. For a replacement, the successor SHALL NOT execute its startup entry or acquire effect authorization until the predecessor has closed. The host SHALL publish readiness only after protected startup reports a normalized ready outcome. A not-ready actor SHALL refuse ordinary events with a typed not-ready result and SHALL NOT queue them for later execution.

#### Scenario: Initial actor becomes ready
- **WHEN** the host starts an actor with no live predecessor and its protected startup entry reports a normalized ready outcome
- **THEN** the host SHALL publish the actor as ready
- **AND** the actor SHALL begin admitting mailbox events and generation-scoped background work

#### Scenario: Replacement remains staged
- **WHEN** a successor has created its state and loaded source while its predecessor is still draining
- **THEN** the successor SHALL NOT execute its startup entry, acquire effect authorization, or publish readiness
- **AND** startup SHALL remain staged until the predecessor has closed

#### Scenario: Not-ready actor receives an event
- **WHEN** an ordinary event is submitted to an actor that the host has not published as ready
- **THEN** the runtime SHALL return a typed not-ready result
- **AND** the event SHALL NOT be queued or executed

### Requirement: Actor failure is latched and classified by containment scope
The host SHALL own a per-actor failure latch. A protected ordinary event or background-task error SHALL be normalized and contained to that event or task unless it is a startup, lifecycle, quota, interruption, ownership-integrity, or other generation-fatal failure. A fatal actor failure SHALL latch the actor as failed, prevent further Lua entry, and project the actor as unavailable without terminating unrelated actors. A failed host operation SHALL resume its owning coroutine with a normalized failure and SHALL NOT by itself latch the actor. The runtime SHALL NOT claim containment for a native engine or bridge defect, unprotected panic, native memory corruption, unrecoverable process OOM, or Android process death.

#### Scenario: Ordinary Lua error is local
- **WHEN** an ordinary event handler or background task raises a string or non-string error under protected execution
- **THEN** the runtime SHALL return or record a bounded normalized failure for that event or task
- **AND** the owning actor SHALL remain ready unless the callback is explicitly classified as lifecycle-critical
- **AND** unrelated actors SHALL remain operational

#### Scenario: Host operation failure resumes locally
- **WHEN** a yielded host operation completes with a normalized failure
- **THEN** the runtime SHALL resume the owning coroutine exactly once with that failure
- **AND** it SHALL NOT latch the actor merely because the operation failed

#### Scenario: Fatal actor failure latches the generation
- **WHEN** startup fails or an actor reaches an instruction, ownership-integrity, or other generation-fatal outcome under protected execution
- **THEN** the host SHALL latch the actor as failed and prevent further Lua entry
- **AND** it SHALL close the failed generation deterministically
- **AND** unrelated actors SHALL remain operational

#### Scenario: Failure is outside the containment ceiling
- **WHEN** execution encounters a native engine or bridge defect, unprotected panic, native memory corruption, unrecoverable process OOM, or Android process death
- **THEN** the runtime SHALL make no claim that the failure is isolated to one actor or that the host process survives

#### Scenario: Failed actor is replaced
- **WHEN** a failed actor is replaced by a fresh generation for the same channel instance
- **THEN** the successor SHALL acquire a fresh Lua state and SHALL NOT inherit the failed actor's volatile state or suspended coroutines
- **AND** the failed actor SHALL close deterministically
### Requirement: Instruction policy interrupts pure-Lua execution within a finite bound
The runtime SHALL enforce instruction-count interruption through an instruction-count hook installed by trusted bootstrap code on the main execution context and on every coroutine created through the bootstrap's overridden `coroutine.create`/`coroutine.wrap`. The hook SHALL observe a host-settable per-actor watchdog flag and interrupt pure-Lua execution exceeding the configured instruction budget. The `debug` library SHALL remain unavailable to package code so packages cannot remove or replace the hook. Interruption SHALL normalize the affected actor as failed or closing, SHALL permit deterministic actor teardown, and SHALL leave unrelated actors usable. The hook SHALL NOT be claimed to preempt a blocking native host function. Instruction budget, hook interval, watchdog timeout, and operation-specific budgets SHALL be internal configurable policy and SHALL NOT become a public compatibility promise.

#### Scenario: Lua executes an infinite loop
- **WHEN** an actor's callback enters an infinite pure-Lua loop
- **THEN** the runtime SHALL interrupt it within a finite instruction and elapsed-time bound
- **AND** the affected actor SHALL close without relying on cooperative Lua return
- **AND** another actor SHALL continue executing

#### Scenario: Child coroutine escapes the hook
- **WHEN** package code creates a coroutine through any available creation path and that coroutine runs an infinite loop
- **THEN** the bootstrap-overridden creation path SHALL have installed the interrupt hook on the child coroutine
- **AND** the runtime SHALL interrupt the child coroutine within the same finite bound

#### Scenario: Host operation would block
- **WHEN** a host operation represents work that cannot complete in a deliberately small bounded native call
- **THEN** the runtime SHALL yield an opaque operation token before that work executes
- **AND** it SHALL NOT block the Lua execution thread waiting for external completion
### Requirement: Runtime replacement drains the predecessor before publishing the successor
When a channel definition change or reconciliation replaces runtime generation G with generation H, the host SHALL NOT publish H as ready until G has stopped new admission, completed any committed input terminal callback, cancelled or drained its descendant background tasks and suspended operations, revoked its generation-bound effects, and closed. The successor H SHALL acquire a fresh Lua state and SHALL NOT inherit G's suspended coroutines, volatile tasks, or prior-generation authorization. Durable host records SHALL be preserved across the replacement without preserving Lua states or volatile runtime context.

#### Scenario: Successor waits for predecessor drain
- **WHEN** reconciliation installs generation H while generation G has committed input, suspended operations, or background tasks
- **THEN** the host SHALL prevent new preparation through G
- **AND** H SHALL NOT be published ready until G's committed input has completed its terminal callback and G's descendants have been cancelled or drained
- **AND** G SHALL close before H is published ready

#### Scenario: Successor does not inherit volatile state
- **WHEN** generation H is constructed to replace generation G
- **THEN** H SHALL acquire a fresh Lua state with its own globals, module cache, and coroutines
- **AND** H SHALL NOT resume G's suspended coroutines or adopt G's operation tokens
- **AND** durable host records SHALL remain available to H through explicit host recovery

#### Scenario: Predecessor effects are revoked
- **WHEN** G closes during replacement
- **THEN** the host SHALL revoke G's generation-bound capability leases and outstanding effects
- **AND** a late completion bearing G's generation SHALL be classified as stale without entering H's Lua state

### Requirement: Actor closure is deterministic and idempotent
Closing an actor SHALL atomically stop admission of new events, invalidate queued events, request cancellation of executing callbacks and their child work, wait only within a configured closure bound, revoke instance-scoped capability leases, invoke the actor's terminal close helper at most once, and publish terminal closure exactly once. An accepted committed input target SHALL receive its required terminal event before the actor is terminally closed. Closure SHALL win races against later completions, and repeated close requests SHALL observe the same terminal closed state.

#### Scenario: Idle actor closes
- **WHEN** an idle actor is retired or the host shuts down
- **THEN** the runtime SHALL stop admission, invalidate queued work, revoke capability leases, invoke terminal close at most once, and publish closure in the defined order

#### Scenario: Actor closes with a committed target
- **WHEN** an actor is retired while one of its input targets remains committed
- **THEN** the host SHALL refuse new preparation for that generation
- **AND** it SHALL preserve the generation until the committed target receives one terminal release, cancellation, or failure
- **AND** it SHALL then perform terminal actor closure exactly once

#### Scenario: Completion arrives after close
- **WHEN** an actor closes while an operation is suspended and its completion arrives later
- **THEN** the runtime SHALL reject the completion as closed or stale
- **AND** it SHALL NOT access freed Lua state or coroutine memory
- **AND** repeated close requests SHALL remain idempotent

#### Scenario: Executing callback ignores closure cancellation
- **WHEN** closure cancellation does not stop an executing callback within the configured closure bound
- **THEN** closure SHALL complete without waiting indefinitely
- **AND** the callback's generation SHALL remain invalidated so that any later result or effect is rejected

### Requirement: Service shutdown and fresh-generation recovery preserve durable records without volatile state
On service or process shutdown, the host SHALL prevent new actor preparation, terminate and await committed input targets, cancel or detach active generation-bound effects, and close all live actors. On service or process restart, the host SHALL allocate fresh generation identities for reconstructed actors and SHALL NOT preserve prior Lua states, suspended coroutines, volatile tasks, or prior-generation authorization. Persisted durable run and message records MAY be reconciled only through an explicit host recovery operation under a fresh generation and SHALL NOT authorize callbacks from the prior process.

#### Scenario: Service shuts down
- **WHEN** the foreground service shuts down
- **THEN** the host SHALL prevent new actor preparation
- **AND** it SHALL terminate and await committed input targets before releasing their leases
- **AND** it SHALL close all live and retired actors exactly once

#### Scenario: Service restarts with a nonterminal run
- **WHEN** the service restarts while a durable run is queued, running, waiting for a tool, synthesizing, or pending playback
- **THEN** the host SHALL reject callbacks carrying the old generation identity
- **AND** the host SHALL retain persisted durable run and message state required by the durable-run contract
- **AND** any recovery under the new generation SHALL be explicit and SHALL NOT import old volatile conversation as runtime context

#### Scenario: Old completion arrives after restart
- **WHEN** a completion from the old process arrives after a new generation is installed
- **THEN** the host SHALL classify it as stale or cancelled
- **AND** it SHALL NOT publish a response, play audio, execute a tool, advance a current run, or mutate current runtime status

### Requirement: Ordinary startup creates no Lua state without a future Lua-backed provider
The change SHALL NOT register a Lua channel provider. Ordinary application startup SHALL NOT create a Lua actor or Lua state for any channel instance. Existing Kotlin providers SHALL remain behaviorally supported and SHALL NOT acquire a Lua runtime. A Lua actor SHALL be created only when a future package and API change supplies a Lua-backed provider for a channel instance. The runtime SHALL NOT alter persisted channel definitions, provider registration, channel routing, PTT, audio, UI, foreground-service, or release behavior.

#### Scenario: Application starts without a Lua provider
- **WHEN** the application starts and uses existing Kotlin channels normally
- **THEN** no Lua actor or Lua state SHALL be created by ordinary production channel startup
- **AND** existing channel and service behavior SHALL remain unchanged

#### Scenario: Future Lua-backed provider is registered
- **WHEN** a future change registers a Lua-backed provider and the catalogue contains a definition referencing it
- **THEN** that provider's runtime SHALL participate in selection, readiness, input routing, and ordered projections through the same registry and framework contracts
- **AND** the runtime SHALL create one Lua actor for that provider's channel runtime generation

### Requirement: Runtime mechanisms remain distinct from plugin policy and host-native capabilities
The runtime SHALL own scheduling, mailbox admission, coroutine continuation, typed request ownership, instruction/memory policy, failure containment, resolver-state containment, durable-work effect execution ownership, replacement draining, and deterministic teardown. The generic durable-work subsystem SHALL own opaque FIFO admission, effect evidence, recovery safety, epochs, quotas, and tombstones without interpreting payloads. Lua SHALL own package protocol, provider requests/responses, retry decisions, polling, arbitrary mounted-storage layout, conversation state, tool policy, and reconstruction from committed generic effect results. Every blocking operation SHALL yield an opaque typed request and remain lifecycle/capability/resource authorized. No actor/runtime component SHALL embed package-specific policy, OpenAI/Journal operation kinds, provider endpoints/models/tools, package migration, or plugin-selected native transport.

#### Scenario: Blocking host operation is requested
- **WHEN** Lua requests an effect that cannot complete in a deliberately small bounded native call
- **THEN** the runtime SHALL yield an opaque typed request before executing it
- **AND** the Lua thread SHALL not block waiting for completion

#### Scenario: OpenAI policy is expressed in Lua
- **WHEN** the external package constructs requests, interprets responses, iterates tools, or rebuilds a safe Job from committed effects
- **THEN** policy SHALL execute within actor bounds
- **AND** host scheduling, durable evidence, ownership, authorization, and containment SHALL remain authoritative without OpenAI semantics

#### Scenario: Generic work survives restart
- **WHEN** process restart finds an item proven safe to reclaim
- **THEN** a fresh actor MAY receive the opaque payload and committed effect results through public work methods
- **AND** no predecessor Lua stack, coroutine, module cache, or conversation SHALL be restored

### Requirement: Conformance and device evidence are recorded without public normative constants
The change SHALL record conformance outcomes and device evidence for the promoted runtime, including state creation and close latency, coroutine suspension and resumption latency, instruction-interruption latency and overhead, allocator-denial behavior, multi-actor behavior, and cancellation and close race results. Numeric observations SHALL be evidence for internal policy and SHALL NOT become a public plugin compatibility promise. The change SHALL NOT silently convert one observed value into a normative plugin limit.

#### Scenario: Runtime completes the conformance suite
- **WHEN** the promoted runtime completes host and Android instrumentation experiments
- **THEN** the recorded evidence SHALL identify every passed and failed correctness gate
- **AND** it SHALL contain enough version and parameter information to rerun the experiments

#### Scenario: Numeric measurement varies across runs
- **WHEN** device measurements differ between executions
- **THEN** the evidence SHALL preserve the observed range or distribution and test conditions
- **AND** the change SHALL NOT promote a single observed value into a stable public timing or memory guarantee

### Requirement: Typed request registry replaces operation-label argument encoding
Each actor state SHALL own a bounded registry of immutable typed host-operation requests. A public module SHALL validate Lua arguments, resolve current userdata, create one request with kind and bounded payload, and yield only an unforgeable request token. Paths, text, JSON, audio/mount tokens, codec parameters, and platform identifiers SHALL not be concatenated into operation labels. The host SHALL claim each request at most once and SHALL reject unknown, duplicate, foreign, stale, cancelled, or closed tokens without executing an effect.

#### Scenario: Filesystem operation is admitted
- **WHEN** a valid `fs.write_text` call is admitted
- **THEN** the state registry SHALL retain its bounded typed payload
- **AND** the yielded outcome SHALL expose only opaque operation identity

#### Scenario: Completed request is claimed twice
- **WHEN** the dispatcher attempts to claim a terminal request again
- **THEN** the actor SHALL return a typed stale/duplicate outcome
- **AND** it SHALL not execute or resume again

### Requirement: Generic host requests share exact-once actor lifecycle
Every typed request SHALL carry the owning state, actor generation, execution owner, operation ID, kind, eligibility, and one atomic terminal gate. Only its live owner MAY receive completion. Deadline, input cancellation, task cancellation, capability/resource revocation, generation close, and normal completion SHALL race through that gate. Generation close SHALL discard suspended coroutines, invalidate pending requests/userdata, release bounded payloads, and suppress late completions. A request registry SHALL not grow without finite per-execution, per-generation, and process limits.

#### Scenario: Generation closes with storage request pending
- **WHEN** close wins before a document-provider request completes
- **THEN** the actor SHALL discard the coroutine and request payload without re-entry
- **AND** late provider completion SHALL not mutate successor state or return another result

### Requirement: Deferred tasks are reserved by input and committed by terminal success
An input execution SHALL be able to reserve bounded task capacity for `runtime.defer(function)` without running the function. The actor SHALL associate the reservation with that exact input owner. Exact `{ok=true}` terminal commit SHALL transfer the function into a new managed-task owner and ready queue after input completion. Failure, malformed return, throw, cancellation, timeout, retirement, or close before commit SHALL discard the reservation/function. Deferred tasks SHALL count against task bounds, own their yielded requests/audio independently, and follow ordinary task failure/cancellation rules.

#### Scenario: Input commits deferred task
- **WHEN** a live input with one admitted defer returns exact success
- **THEN** the actor SHALL terminally complete the input before making the task runnable
- **AND** the task SHALL have a distinct execution owner

#### Scenario: Input fails after defer
- **WHEN** the input throws or returns failure after reservation
- **THEN** the actor SHALL discard the deferred function without execution
- **AND** task capacity SHALL be released

### Requirement: Deferred tasks cannot inherit input-scoped userdata ownership
Closure capture SHALL not transfer opaque audio ownership. A deferred task that presents an audio token owned by its input predecessor SHALL receive a foreign/stale error before host effect. Generation-owned mount handles MAY be retained and used by the deferred task while current. The task MAY create its own audio ownership by successfully reopening a stored Recording.

#### Scenario: Deferred closure captures capture audio
- **WHEN** deferred code calls transcription/export with the predecessor input Recording
- **THEN** audio registry resolution SHALL reject it before effect
- **AND** mount handles captured from the same generation MAY remain usable under live revalidation

### Requirement: Actor broker carries typed keyboard-output requests
The actor kernel SHALL validate Lua keyboard-output arguments and register one bounded typed host-operation request for `send_text` or `send_key` without encoding text, profile, key, JSON, transport data, or operation identity into a yielded label. The request SHALL be bound to the Lua state, runtime generation, execution owner, and one opaque exactly-once claim identity. Kotlin SHALL claim the typed request before capability acquisition or queue admission, and duplicate, foreign, malformed, cancelled, closed, or stale claims SHALL fail before effect.

#### Scenario: Valid text request yields
- **WHEN** an eligible Lua execution calls `send_text` with valid bounded arguments
- **THEN** the kernel SHALL register one typed text request and yield only its opaque request identity
- **AND** the host SHALL obtain text and profile only by exactly-once typed claim

#### Scenario: Request identity is claimed twice
- **WHEN** host code claims a keyboard-output request whose claim was already consumed
- **THEN** the actor boundary SHALL return a typed stale or duplicate result
- **AND** no second capability acquisition, queue admission, output, or Lua resume SHALL occur

#### Scenario: Request belongs to another execution owner
- **WHEN** a completion, cancellation, or claim uses a foreign state, generation, or execution owner
- **THEN** the actor SHALL reject it before Lua entry or host effect
- **AND** the legitimate owner's request SHALL remain unchanged

### Requirement: Keyboard-output suspension and completion are exactly once
A keyboard-output request SHALL suspend only its owning input, SOS, or managed-task coroutine, release the actor execution slot, and race host completion, timeout, owner cancellation, generation revocation, and close through one idempotent terminal gate. While the generation remains live, the winning terminal SHALL resume the owner exactly once with the normalized result. Managed-task cancellation or generation close SHALL discard the coroutine without re-entering Lua and SHALL make every later completion stale.

#### Scenario: Host completes while another coroutine runs
- **WHEN** keyboard output reaches a terminal outcome while another coroutine owns Lua entry
- **THEN** the actor SHALL enqueue the original owner's continuation rather than entering Lua concurrently
- **AND** it SHALL resume only when the actor execution slot becomes available

#### Scenario: Completion races timeout
- **WHEN** host completion and the keyboard-output deadline race
- **THEN** exactly one terminal outcome SHALL win
- **AND** the losing terminal SHALL not resume Lua or authorize another physical effect

#### Scenario: Managed task is cancelled while output may be partial
- **WHEN** a managed task is cancelled after physical output may have begun
- **THEN** the actor SHALL discard the task coroutine without re-entry
- **AND** the host keyboard-output layer SHALL still classify and clean up the physical operation exactly once

### Requirement: Resolver actors use a restricted one-shot execution mode
The actor kernel SHALL support a resolver mode that creates one independent bounded state, validates/loads the declared resolver module under the effect guard, invokes exactly one protected yield-capable `resolve` function, permits only resolver-authorized host requests, returns one validated terminal result, and closes deterministically. Resolver actors SHALL have separate admission, instruction, memory, task, request, and deadline policy and SHALL not enter the ordinary channel mailbox/startup/readiness lifecycle or create background/deferred tasks.

#### Scenario: Resolver suspends for HTTPS
- **WHEN** the resolve function issues an authorized HTTP request
- **THEN** the resolver actor SHALL release the execution slot and resume the same resolver coroutine at most once
- **AND** closure or deadline SHALL suppress late completion

#### Scenario: Resolver tries to spawn
- **WHEN** resolver code calls `runtime.spawn` or `defer`
- **THEN** the operation SHALL fail before task admission
- **AND** the one-shot actor SHALL remain bounded

### Requirement: Durable work effect scopes integrate with actor scheduling
A managed task calling `Job:effect` SHALL create a task-local protected effect scope after the durable subsystem commits the start marker. Authorized operations from the function SHALL carry both managed-task and work-effect ownership. The actor SHALL permit repeated suspension/resumption, but at most one active work effect per Job and no nested effect. After a valid terminal Lua return, normalization and durable commit SHALL finish before the outer task resumes. Close/interruption after start but before commit SHALL discard Lua and notify work reconciliation of ambiguity exactly once.

#### Scenario: Effect yields multiple times
- **WHEN** one effect function reads a secret and then performs HTTPS
- **THEN** each host request SHALL suspend/resume under the same effect owner
- **AND** one normalized function result SHALL be committed before outer resumption

#### Scenario: Generation closes during effect
- **WHEN** replacement closes the actor after effect start
- **THEN** the function SHALL not resume in a successor
- **AND** the durable job SHALL become indeterminate unless a terminal result was already committed

### Requirement: Actor teardown invalidates new opaque values and releases bounded payloads
Generation/resolver closure SHALL invalidate Queue, Job, Effect, SecretReference, and JSON Null tokens owned by the closing state; cancel receives and host requests; release retained request/effect-function references; and suppress late completions. Durable queue records and committed results SHALL remain owned by the durable subsystem, not the actor. Actor teardown SHALL not delete safe queued work, terminal tombstones, package profiles, or protected secrets except through their explicit lifecycle contracts.

#### Scenario: Worker is suspended in receive at close
- **WHEN** its generation closes
- **THEN** the receive coroutine and Queue token SHALL be discarded
- **AND** durable queue records SHALL be reconciled according to epoch cause

