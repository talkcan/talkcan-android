## Purpose

Define deterministic, per-generation serialization and lifecycle-safe invocation boundaries for channel runtime callbacks and cooperative actor continuations.
## Requirements
### Requirement: Runtime callbacks are serialized per instance
The host SHALL invoke provider and runtime callbacks through a host-owned invocation boundary that permits at most one host-admitted callback to execute at a time for a runtime generation. Host-admitted callbacks SHALL be ordered deterministically in admission order, and each executing callback SHALL observe the effects of the preceding completed callback before it begins. Runtime callbacks SHALL NOT be invoked directly from arbitrary callers.

Where the runtime is a cooperative actor runtime, the invocation boundary SHALL distinguish host-to-runtime adapter admission from in-actor scheduling. A host callback SHALL occupy the serialized adapter queue only while it admits an event and runs that event's current Lua slice. If the slice yields, the adapter callback SHALL return a normalized admitted or yielded result and release its invocation slot; the suspended coroutine remains actor-owned and does not keep the host callback active. Later host callbacks MAY then admit later events in FIFO order. Actor continuations resume through the bounded ready queue and do not constitute new host-admitted callbacks. At most one native Lua entry SHALL execute for the generation regardless of queued callbacks, ready continuations, or suspended coroutines. Separate runtime instances MAY execute independently.

When a yielded actor continuation is resumed synchronously from inside an executing serialized callback, the boundary SHALL treat that continuation as already covered by the callback's execution serialization. The continuation resume SHALL NOT block on, or attempt to re-acquire, the non-reentrant execution lock already held by the enclosing callback in a way that deadlocks the runtime generation; liveness and closed-state checks for that generation SHALL still apply.

#### Scenario: Concurrent events target one runtime
- **WHEN** two runtime callbacks are admitted concurrently for the same runtime generation
- **THEN** the invocation boundary SHALL execute them without overlap in deterministic admission order
- **AND** each callback SHALL observe the effects of the preceding completed callback

#### Scenario: Events target different runtimes
- **WHEN** independent callbacks are admitted for different live runtime instances
- **THEN** serialization of either instance SHALL NOT require execution under the other instance's callback queue or lock

#### Scenario: Suspended Lua entry interleaves other coroutines
- **WHEN** a Lua entry yields a host-operation token and suspends on a cooperative actor runtime
- **THEN** the actor MAY resume other suspended coroutines through its bounded ready queue while the entry remains suspended
- **AND** native Lua entry SHALL NOT overlap concurrently for that generation
- **AND** the resumed coroutines SHALL NOT be treated as newly host-admitted callbacks for adapter ordering

#### Scenario: Yield releases the adapter invocation slot
- **WHEN** a host-admitted actor event yields an opaque operation token
- **THEN** the adapter callback SHALL return its normalized admitted or yielded result without waiting for the operation to finish
- **AND** the invocation boundary SHALL permit the next admitted host callback to run
- **AND** the suspended coroutine SHALL remain owned by the actor ready/suspended scheduler

#### Scenario: Host operation completes while another coroutine runs
- **WHEN** a suspended Lua entry's host-operation token completes while a different coroutine is running in the same actor
- **THEN** the actor SHALL NOT resume the suspended entry concurrently with the running coroutine
- **AND** it SHALL resume the suspended entry only when native Lua entry is again available for that generation

#### Scenario: Continuation resumed from within a serialized callback does not self-deadlock
- **WHEN** an executing host-admitted callback synchronously resumes a yielded actor continuation through the invocation boundary
- **THEN** the boundary SHALL run that continuation under the enclosing callback's existing execution serialization
- **AND** it SHALL NOT block waiting for a non-reentrant execution lock already held by that same callback
- **AND** the generation SHALL complete both the callback and the continuation without a timeout-based invalidation
- **AND** continuations resumed from outside any serialized callback SHALL still acquire the execution lock and remain serialized against host-admitted callbacks

### Requirement: Invocation admission and execution are bounded
The host SHALL use a bounded queue and an explicit deadline or timeout policy for provider construction and runtime callback work. When the queue has no capacity, the host SHALL reject new work with a typed busy result rather than block an unbounded number of callers. When an invocation exceeds its deadline, the host SHALL cancel it and return a typed timeout result without waiting indefinitely for provider or runtime code.

For a cooperative actor runtime, the host SHALL distinguish the active execution budget of a Lua entry from the yielded wait time of a suspended operation. An active execution budget bounds pure-Lua computation while the VM is entered; a yielded operation wait bounds the time a suspended coroutine waits for a host-operation token to complete. The host SHALL apply the active execution budget to a resumed coroutine only while it is running native Lua entry, and SHALL NOT charge yielded wait time against the active execution budget. A suspended operation whose wait exceeds its configured operation deadline SHALL be completed with a typed timed-out operation result, and the owning coroutine SHALL be resumed exactly once with that normalized result. A Lua entry whose active execution budget is exceeded SHALL be interrupted within a measured finite bound and SHALL NOT continue executing Lua instructions.

#### Scenario: Runtime queue is saturated
- **WHEN** a runtime generation's bounded invocation queue cannot admit another callback
- **THEN** the host SHALL reject that callback with a typed busy or unavailable result
- **AND** it SHALL NOT allocate an unbounded waiter or start the rejected callback later

#### Scenario: Callback exceeds its deadline
- **WHEN** a provider or runtime callback does not complete within its configured invocation deadline
- **THEN** the host SHALL request cancellation and complete the caller with a typed timeout result
- **AND** subsequent work SHALL NOT depend on the timed-out callback completing successfully

#### Scenario: Callback completes within bounds
- **WHEN** an admitted callback completes before its deadline
- **THEN** the host SHALL return its normalized result exactly once
- **AND** it SHALL release the invocation slot for the next admitted callback

#### Scenario: Active Lua execution exceeds its budget
- **WHEN** a resumed coroutine executing native Lua entry exceeds the configured active execution budget for that generation
- **THEN** the host SHALL interrupt the Lua entry within a measured finite instruction and elapsed-time bound
- **AND** it SHALL NOT continue executing Lua instructions for that entry past the budget
- **AND** the affected runtime generation SHALL remain closable

#### Scenario: Yielded operation exceeds its wait deadline
- **WHEN** a suspended coroutine waits for a host-operation token longer than the configured operation wait deadline
- **THEN** the host SHALL complete the owning token with a typed timed-out operation result exactly once
- **AND** it SHALL resume the owning coroutine exactly once with that normalized result
- **AND** the active execution budget SHALL NOT be consumed by the yielded wait time

### Requirement: Cancellation is propagated and normalized
The invocation boundary SHALL propagate caller, session, replacement, and shutdown cancellation to queued and executing callback work. Cancellation SHALL remain distinguishable from timeout and non-cancellation failure, SHALL complete the invocation exactly once, and SHALL NOT be converted into success or an untyped exception.

For a cooperative actor runtime, cancellation SHALL reach both the active Lua entry and every suspended operation token owned by the generation. A suspended operation token SHALL accept at most one terminal cancellation or completion; racing cancellation and completion SHALL produce exactly one terminal outcome and SHALL NOT resume Lua more than once. Cancelling an active Lua entry SHALL interrupt it within the measured finite bound used by the active execution budget. Cancelling a generation SHALL invalidate all of its suspended operation tokens without resuming their coroutines for the purpose of producing effects.

#### Scenario: Caller cancels queued work
- **WHEN** a caller cancels an invocation before its callback starts
- **THEN** the host SHALL remove or invalidate the queued callback
- **AND** the callback SHALL NOT execute
- **AND** the caller SHALL receive a typed cancelled result

#### Scenario: Caller cancels executing work
- **WHEN** cancellation reaches a running callback
- **THEN** the host SHALL propagate cancellation to the callback's child work
- **AND** it SHALL complete the invocation with a typed cancelled result exactly once

#### Scenario: Runtime throws cancellation
- **WHEN** a runtime callback terminates with the host's cancellation signal
- **THEN** the invocation boundary SHALL classify the outcome as cancellation rather than runtime failure

#### Scenario: Cancelled suspended operation races completion
- **WHEN** cancellation and completion race for one suspended operation token on a cooperative actor runtime
- **THEN** exactly one terminal outcome SHALL win
- **AND** the owning coroutine SHALL be resumed at most once
- **AND** the losing request SHALL observe a typed cancelled, completed, or stale result

#### Scenario: Generation cancellation reaches suspended operations
- **WHEN** a runtime generation is cancelled while coroutines are suspended on operation tokens
- **THEN** the host SHALL invalidate every suspended operation token for that generation
- **AND** it SHALL NOT resume those coroutines to publish effects into current application state
- **AND** the active Lua entry SHALL be interrupted within the configured finite bound

### Requirement: Callback failures are contained and mapped
The host SHALL catch every non-fatal provider or runtime callback failure at the invocation boundary and map it to a typed channel failure containing the operation phase and affected instance without exposing an implementation stack trace as channel data. A callback failure SHALL NOT escape into the registry, audio-session coordinator, main thread, or another runtime's invocation queue.

#### Scenario: Runtime callback throws
- **WHEN** a runtime callback throws a non-cancellation exception
- **THEN** the host SHALL complete that invocation with a typed runtime failure
- **AND** it SHALL perform the caller's required terminal cleanup exactly once
- **AND** unrelated runtime instances SHALL remain available

#### Scenario: Provider constructor throws
- **WHEN** provider runtime construction throws a non-cancellation exception
- **THEN** the host SHALL map it to a typed provider-construction failure for that definition
- **AND** it SHALL project the affected instance as unavailable rather than terminate catalogue reconciliation

### Requirement: Callbacks execute outside registry locks and the Android main thread
The host SHALL NOT call provider code, runtime code, capability operations, cancellation handlers, or close callbacks while holding a runtime-registry or catalogue synchronization lock. Potentially blocking or externally supplied callbacks SHALL execute on a bounded host-owned worker dispatcher and SHALL NOT execute on the Android main thread. Locks SHALL protect only short host-owned state transitions and SHALL be released before awaiting callback completion.

#### Scenario: Registry reconciliation constructs a runtime
- **WHEN** reconciliation determines under synchronization that a runtime must be created, updated, retired, or closed
- **THEN** it SHALL record the required transition and release the synchronization lock before invoking provider or runtime code
- **AND** it SHALL validate the transition is still current before publishing its result

#### Scenario: Runtime callback is initiated by the UI thread
- **WHEN** an Android main-thread action requests a provider or runtime callback
- **THEN** the invocation boundary SHALL dispatch callback execution to its bounded worker context
- **AND** the Android main thread SHALL NOT block waiting synchronously for callback completion

#### Scenario: Callback re-enters host state
- **WHEN** a runtime callback publishes status or invokes a host capability
- **THEN** the host SHALL process that interaction without requiring the callback to re-enter a registry lock held by its invoker

### Requirement: Runtime closure follows deterministic ordering
Closing a runtime generation SHALL atomically stop admission of new callbacks, invalidate queued callbacks, request cancellation of executing callbacks and their child work, wait only within the configured closure bound, revoke instance-scoped capability leases, invoke the runtime's terminal close callback at most once, and publish terminal closure exactly once. An accepted committed input target SHALL receive its required terminal event before its runtime generation is terminally closed.

#### Scenario: Idle runtime closes
- **WHEN** an idle runtime generation is retired or the host shuts down
- **THEN** the host SHALL stop admission, invalidate queued work, revoke capability leases, invoke terminal close at most once, and publish closure in the defined order

#### Scenario: Runtime closes with a committed target
- **WHEN** a runtime is retired while one of its input targets remains committed
- **THEN** the host SHALL refuse new preparation for that generation
- **AND** it SHALL preserve the generation until the committed target receives one terminal release, cancellation, or failure
- **AND** it SHALL then perform terminal runtime closure exactly once

#### Scenario: Executing callback ignores cancellation
- **WHEN** closure cancellation does not stop an executing callback within the closure bound
- **THEN** host closure SHALL complete without waiting indefinitely
- **AND** the callback's generation SHALL remain invalidated so that any later result or effect is rejected

#### Scenario: Close is requested repeatedly
- **WHEN** replacement, lease release, and host shutdown each request closure of the same runtime generation
- **THEN** only the first request SHALL perform terminal close and publication
- **AND** all requests SHALL observe the same terminal closed state

### Requirement: Late results and effects are suppressed
The host SHALL gate callback result publication and capability effects by the runtime generation's live state. Once work is cancelled, timed out, superseded, retired for terminal closure, or closed, its later completion SHALL NOT publish readiness, execution status, playback, text output, persistence, or any other effect into current application state. Suppression SHALL occur at the host boundary even when provider or runtime code does not cooperate with cancellation.

#### Scenario: Timed-out callback returns later
- **WHEN** a callback returns a value after its invocation has completed as timed out
- **THEN** the host SHALL discard the late value
- **AND** it SHALL NOT convert the timeout into success or publish effects from that value

#### Scenario: Replaced generation publishes status
- **WHEN** an old runtime generation attempts to publish status after a replacement generation is current
- **THEN** the host SHALL reject the publication
- **AND** the replacement generation's state SHALL remain unchanged

#### Scenario: Closed runtime invokes a capability
- **WHEN** closed or invalidated runtime work attempts a host capability operation
- **THEN** the capability boundary SHALL return a typed closed or cancelled result
- **AND** it SHALL produce no hardware, transport, audio, text-output, persistence, or state-publication effect

### Requirement: Invocation hardening does not embed a script engine
The invocation boundary SHALL treat runtime implementations as opaque callback providers and SHALL NOT require or introduce a Lua interpreter, Lua scheduler, package loader, package execution policy, or script API. Its serialization, bounds, cancellation, failure mapping, confinement, closure, and effect-gating guarantees SHALL apply to Kotlin built-in runtimes and any future conforming runtime provider.

#### Scenario: Kotlin built-in callback is invoked
- **WHEN** a built-in Kotlin runtime callback enters the invocation boundary
- **THEN** the host SHALL apply the same queueing, timeout, cancellation, failure, thread, closure, and late-effect rules
- **AND** no scripting subsystem SHALL be initialized

### Requirement: Input-deferred work commits after terminal success
The runtime invocation boundary SHALL associate every `runtime.defer` reservation with the exact committed input invocation that created it. Deferred code SHALL not enter Lua, acquire a host capability, start a storage/audio operation, or become visible as a managed task before that input returns exact success and the boundary commits its terminal SUCCESS. After commit, the boundary SHALL admit the function as a new generation-owned task outside the input callback. Any failure, malformed return, throw, cancellation, timeout, replacement, or shutdown that wins before terminal success SHALL discard all reservations for that input.

#### Scenario: Successful input starts deferred work afterward
- **WHEN** an input callback reserves a deferred function and returns exact success
- **THEN** the boundary SHALL commit input SUCCESS before allowing the deferred task to run
- **AND** the task SHALL no longer occupy the committed input callback invocation

#### Scenario: Input is cancelled after reservation
- **WHEN** cancellation wins before exact terminal success
- **THEN** the boundary SHALL discard the deferred reservation without executing its function
- **AND** cancellation SHALL complete exactly once

### Requirement: Deferred work participates in predecessor draining
Once released after input success, a deferred task SHALL be an ordinary descendant of its runtime generation. Replacement or shutdown SHALL stop new task/request admission, cancel or drain released deferred tasks under the configured close bound, revoke their capability/resource authority, close the actor, and only then publish a successor. An unreleased reservation SHALL be discarded. Durable files committed by a package before cancellation MAY remain for successor recovery, but volatile closure/task/request state SHALL not cross generations.

#### Scenario: Replacement begins during deferred derivation
- **WHEN** a released deferred task is suspended in a host operation and replacement retires its generation
- **THEN** predecessor close SHALL cancel/discard the task and suppress its late completion
- **AND** the successor SHALL receive no predecessor closure, coroutine, request token, or audio handle

### Requirement: Typed host-operation waits remain distinct from active callback execution
A filesystem, audio-file, or existing semantic-audio request yielded by an input or managed task SHALL release the serialized adapter/Lua execution slot. Its finite operation wait deadline SHALL not be charged as active Lua execution time. Resumption SHALL reenter only the owning current execution under the normal active slice budget. Request payload size or remote-provider latency SHALL not cause an unbounded invocation queue or main-thread wait.

#### Scenario: Document provider is slow
- **WHEN** a filesystem request remains pending while another actor event is ready
- **THEN** the suspended owner SHALL not retain the Lua execution slot or Android main thread
- **AND** the operation deadline SHALL terminate the wait with one typed outcome if it does not complete

### Requirement: Recoverable preparation completes before input commitment
When cached Lua readiness is false with a valid nonempty preparation request, the runtime invocation boundary SHALL execute registered capability preparers under the current `PREPARE_INPUT` generation gate before returning input acceptance. It SHALL apply a finite host policy deadline, propagate release/cancellation/replacement/shutdown, refresh readiness once after successful preparation, and accept input only when refreshed readiness is true. The host SHALL NOT commit an input target, play the ready beep, or start capture while preparation is pending or unsuccessful.

#### Scenario: Preparation establishes readiness
- **WHEN** current bounded preparation succeeds and refreshed Lua readiness returns true
- **THEN** `PREPARE_INPUT` SHALL return an accepted target bound to that generation
- **AND** ordinary route preflight, ready beep, and capture MAY proceed afterward

#### Scenario: Preparation fails or times out
- **WHEN** any requested preparer fails, times out, is cancelled, or completes stale
- **THEN** `PREPARE_INPUT` SHALL return a typed refusal or unavailability
- **AND** no accepted target, ready beep, capture, or later effect SHALL be produced for that attempt

#### Scenario: Release races preparation
- **WHEN** PTT release or cancellation wins while preparation is suspended
- **THEN** the input attempt SHALL remain terminal and uncommitted
- **AND** later preparation or readiness completion SHALL be suppressed

### Requirement: SOS supports bounded yielded operations
The `HANDLE_SOS` invocation phase SHALL support a host-managed Lua SOS coroutine that may yield authorized opaque operations, release the serialized Lua execution slot while suspended, and resume under the same generation gate. SOS SHALL have a finite phase/operation deadline, exact terminal success or contained failure, and idempotent cancellation. It SHALL NOT hold runtime-registry or catalogue locks, execute on the Android main thread, or admit spawn/defer work.

#### Scenario: SOS yields for keyboard output
- **WHEN** a Lua SOS callback yields an authorized keyboard-output request
- **THEN** the invocation boundary SHALL release its execution slot while the host operation is pending
- **AND** it SHALL resume the SOS owner exactly once when the terminal result wins and the generation remains live

#### Scenario: Generation closes during yielded SOS
- **WHEN** close, replacement, or service shutdown revokes the generation while SOS is suspended
- **THEN** the boundary SHALL cancel or detach the operation according to its effect state and discard predecessor continuation
- **AND** late completion SHALL not re-enter Lua, emit another key, or mutate a successor

### Requirement: Selection changes do not cancel generation-authorized keyboard output
The invocation boundary SHALL NOT treat active-channel selection changes as cancellation or revocation of keyboard-output work owned by a live generation. It SHALL continue to gate results by execution owner and generation. Only explicit owner cancellation or generation retirement SHALL invalidate the operation.

#### Scenario: Selection changes during managed-task output
- **WHEN** a managed task is suspended in keyboard output and another channel becomes selected
- **THEN** the original operation SHALL remain pending under its existing generation gate
- **AND** its terminal result SHALL not be published to the selected channel

### Requirement: Input invocation supports durable work submission
The `HANDLE_INPUT` phase SHALL permit a package with declared `work.queue` to submit bounded work through its input execution owner. Submission MAY yield and release the serialized Lua slot, but the input phase SHALL not report exact success until durable admission commits. The phase deadline, cancellation, replacement, and terminal-result gate SHALL cover submission. Successful admission SHALL detach the queue item from PTT/audio/callback ownership; no accepted work MAY retain a Recording, route, callback, or input execution token.

#### Scenario: Input admits asynchronous turn
- **WHEN** a committed capture is transcribed and queue submission commits within the phase deadline
- **THEN** exact `{ok=true}` SHALL complete the input callback and release route ownership
- **AND** managed queue processing MAY continue after the PTT operation ends

#### Scenario: Input closes during submission
- **WHEN** generation close wins before durable commit
- **THEN** the callback SHALL be discarded under close semantics
- **AND** no partial queue item SHALL become claimable

### Requirement: Managed workers wait for durable work without polling
A startup-admitted managed task MAY call `Queue:receive` after Ready publication. The receive operation SHALL suspend under actor/task ownership, release the execution slot, and wake only for claimable FIFO work or terminal lifecycle outcome. It SHALL not require periodic `sleep`, busy polling, a retained thread, selection state, PTT activity, or a second service. At most one active Job per queue SHALL be admitted by the durable subsystem.

#### Scenario: Empty queue worker waits
- **WHEN** a worker calls receive on an empty live queue
- **THEN** it SHALL suspend without consuming active Lua instruction budget or an OS thread
- **AND** a later committed submission SHALL wake it under the same generation if still current

### Requirement: Replacement cause determines work-epoch reconciliation
Runtime invocation/reconciliation SHALL distinguish ordinary process reconstruction with unchanged package/configuration/profile revision from intentional SOS, configuration/profile change, package update/rollback/removal, instance deletion, and shutdown. Unchanged restart SHALL allow safe durable work reclamation into a fresh actor. Intentional replacement/reset SHALL retire the prior epoch before successor work admission; safe unstarted items become cancelled and ambiguous effects indeterminate. Successor Ready publication SHALL not race predecessor work authority.

#### Scenario: Profile edit replaces active runtime
- **WHEN** a selected profile revision changes while a Job is active
- **THEN** predecessor admission/effects SHALL be revoked and reconciled before successor readiness
- **AND** no late predecessor result SHALL update successor conversation or status

#### Scenario: Process restarts with queued item
- **WHEN** active package/configuration/profile revisions are unchanged and the item has no ambiguous effect
- **THEN** a fresh worker SHALL receive it in original FIFO order
- **AND** no predecessor Lua state SHALL be restored

### Requirement: Generic work status projects without provider payloads
Runtime snapshots and channel surfaces SHALL derive bounded idle, queued, active, failed, and indeterminate execution state plus queued count from the generic work subsystem where a package declares queues. Projection SHALL remain associated with the channel instance and current work epoch, shall coexist with existing preparation/readiness, and SHALL not expose payloads, effect keys/results, profile values, secrets, provider protocol, or ledger internals.

#### Scenario: Turn remains queued
- **WHEN** one Job is active and another is waiting
- **THEN** the channel projection SHALL report active processing and bounded queued count
- **AND** surfaces SHALL not receive transcript or provider data

