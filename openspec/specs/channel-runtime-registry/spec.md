## Purpose

Defines the host-owned runtime registry that reconciles persisted channel definitions into live runtime instances while preserving committed PTT input targets.

## Requirements

### Requirement: Runtime registry resolves channel instances by ID
The system SHALL maintain a host-owned runtime registry keyed by stable channel instance ID. Every published catalogue definition SHALL have exactly one corresponding registry entry resolved from its stable implementation provider reference. An available provider entry SHALL own the live runtime constructed from the provider-validated configuration payload; a missing, incompatible, migration-failed, construction-failed, or otherwise unavailable provider SHALL produce an explicit unavailable entry for that same instance ID. Input preparation SHALL resolve the selected instance through the registry without exhaustive built-in ID, provider, or channel-kind branches.

#### Scenario: Catalogue definition creates runtime
- **WHEN** a valid catalogue definition has no corresponding registry entry and its referenced provider is available
- **THEN** the registry SHALL ask that provider to construct a runtime from the validated configuration payload
- **AND** it SHALL associate the resulting entry with the definition's stable instance ID

#### Scenario: Selected instance prepares input
- **WHEN** PTT preparation requests a known enabled instance whose runtime is ready
- **THEN** the registry SHALL return that runtime's accepted committed input target

#### Scenario: Provider is unavailable
- **WHEN** a valid catalogue definition references a provider that is missing, incompatible, failed migration, or failed runtime construction
- **THEN** the registry SHALL retain an explicit unavailable entry associated with that definition's stable instance ID
- **AND** the entry SHALL expose a typed actionable reason without discarding the catalogue definition or opaque configuration payload

#### Scenario: Unknown or unavailable instance prepares input
- **WHEN** input preparation references an unknown, disabled, unready, failed, or unavailable registry entry
- **THEN** the registry SHALL return a typed refused or unavailable result
- **AND** capture SHALL NOT start for that request

#### Scenario: Additional provider is registered
- **WHEN** an additional channel implementation provider is registered and the catalogue contains a definition referencing it
- **THEN** its runtime SHALL participate in selection, readiness, input routing, and ordered projections without changing core dispatch or projection code

### Requirement: Runtime state projects readiness and execution status generically
Each registry entry SHALL expose its instance ID, effective display name, availability, readiness, and generic execution status through a runtime snapshot. An unavailable entry SHALL remain in the ordered projection at its catalogue position and SHALL expose a typed unavailability reason. Catalogue and runtime changes SHALL update the ordered application projection without persisting ephemeral availability, readiness, execution status, or failure diagnostics into the channel definition.

#### Scenario: External dependency changes readiness
- **WHEN** a runtime's semantic host dependency becomes available or unavailable
- **THEN** the corresponding runtime snapshot SHALL update readiness
- **AND** the persisted definition SHALL remain unchanged

#### Scenario: Runtime reports processing failure
- **WHEN** channel-domain processing fails
- **THEN** the runtime SHALL expose a failed status associated with that instance ID
- **AND** other runtime instances SHALL remain operational

#### Scenario: Unavailable entry is projected in order
- **WHEN** an ordered catalogue contains a definition whose provider cannot supply a runtime
- **THEN** the registry SHALL project an unavailable snapshot at that definition's catalogue position
- **AND** the snapshot SHALL retain the definition's stable instance ID and effective display name
- **AND** later entries SHALL NOT shift forward as if the unavailable definition were absent

### Requirement: Committed input targets survive catalogue reconciliation
Once a runtime accepts an input target, the registry SHALL hold a committed runtime lease for that target until the target has received and completed exactly one terminal release, cancellation, failure, or failed-start callback. Selection, reorder, update, removal, provider replacement, or shutdown SHALL NOT redirect the session or close the leased runtime. A retired runtime SHALL close only after new preparation has been prevented and its final committed lease has been released.

A successor runtime generation SHALL NOT be published ready or become current for new work for the same channel instance until its predecessor has stopped admission of new callbacks, the predecessor's committed input target has terminated and completed exactly one terminal callback, the predecessor's owned descendants and outstanding effects have drained or been cancelled, the predecessor's instance-scoped capability leases have been revoked, and the predecessor runtime has closed exactly once. While the predecessor drains, the registry SHALL route new preparation to the successor only after the predecessor has closed; it SHALL NOT route new preparation to a predecessor that has stopped admission. The committed terminal callback of the predecessor's target SHALL NOT be redirected to, or replayed against, the successor generation.

#### Scenario: Active selection changes during capture
- **WHEN** another channel becomes active while a committed target is processing a PTT session
- **THEN** the existing session SHALL continue using the original target
- **AND** subsequent sessions SHALL use the newly active runtime

#### Scenario: Committed instance is removed
- **WHEN** a channel instance is removed while one of its targets is committed
- **THEN** the registry SHALL prevent new preparation through the retired entry
- **AND** the target SHALL receive and complete its terminal event
- **AND** the retired runtime SHALL close only after its final committed lease is released

#### Scenario: Runtime is replaced while target is committed
- **WHEN** reconciliation installs a replacement runtime while a target from the previous runtime remains committed
- **THEN** subsequent preparation SHALL use the replacement runtime only after the previous runtime has stopped admission, its committed target has terminated, its descendants and effects have drained or been cancelled, its capability leases have been revoked, and it has closed
- **AND** the previous runtime SHALL remain alive until its final committed target completes and releases its lease
- **AND** the replacement SHALL NOT be published ready for new work before that predecessor closure completes

#### Scenario: Terminal callback releases runtime lease once
- **WHEN** a committed target reaches release, cancellation, failure, or failed start
- **THEN** the host SHALL complete the target's terminal callback before the registry releases that target's runtime lease
- **AND** the registry SHALL release that lease exactly once

#### Scenario: Successor is prepared before predecessor closes
- **WHEN** a successor generation is constructed before its predecessor's committed target has terminated
- **THEN** the registry SHALL NOT publish the successor as ready or current for new work
- **AND** new preparation SHALL NOT be routed to the successor until the predecessor has closed
- **AND** the predecessor's committed terminal callback SHALL NOT be delivered to the successor

### Requirement: Runtime lifecycle is bounded and idempotent
Each runtime SHALL own its channel-domain child work and provide idempotent closure. Registry replacement, removal, and service shutdown SHALL first prevent new preparation, then terminate and await committed targets, then release their leases, and only then cancel and join remaining runtime-owned work and close the runtime exactly once. Closure SHALL prevent late callbacks from mutating current state. The registry SHALL NOT invoke provider or runtime code while holding an internal registry lock.

For a runtime that owns cooperative descendants, such as an actor's suspended coroutines, operation tokens, and ready-queue work, replacement and removal SHALL drain or cancel those descendants and revoke their generation-scoped capability authorizations before the runtime closes. A successor generation SHALL NOT become ready or current for new work until the predecessor has completed this full drain-revoke-close sequence. The registry SHALL NOT persist or restore volatile descendant state, such as suspended coroutines, mailbox entries, operation tokens, or ready-queue positions, across the replacement or across process restart.

#### Scenario: Removed idle runtime closes
- **WHEN** a runtime is removed and has no committed targets
- **THEN** the registry SHALL prevent new preparation and close it exactly once

#### Scenario: Service shuts down
- **WHEN** the foreground service shuts down
- **THEN** the host SHALL prevent new runtime preparation
- **AND** it SHALL terminate and await the active host PTT session before releasing its committed target lease
- **AND** it SHALL close all live and retired runtimes exactly once only after their committed leases are released

#### Scenario: Runtime close callback executes outside lock
- **WHEN** reconciliation or shutdown requires a runtime to close
- **THEN** the registry SHALL select and mark the runtime for closure while synchronized
- **AND** it SHALL invoke and await runtime closure only after releasing its internal lock

#### Scenario: Late completion after closure
- **WHEN** cancelled runtime work completes after the runtime has closed
- **THEN** it SHALL NOT publish status, playback, or other effects into current application state

#### Scenario: Replacement drains cooperative descendants
- **WHEN** reconciliation replaces a runtime that owns suspended coroutines or outstanding operation tokens
- **THEN** the registry SHALL cancel or drain those descendants and revoke their generation-scoped capability authorizations before the predecessor closes
- **AND** the successor SHALL NOT become ready for new work until that drain-revoke-close sequence completes
- **AND** no volatile descendant state SHALL be persisted or restored into the successor


### Requirement: Registry callbacks execute outside internal locks
The registry SHALL establish state transitions and ownership under its internal synchronization, but it SHALL invoke provider migration, validation, runtime construction, preparation, update, target, and close callbacks only after releasing registry locks. Callback completion SHALL be committed back under synchronization only if the originating entry generation is still current. A slow, reentrant, suspended, or failing callback SHALL NOT block unrelated registry lookup, projection, reconciliation, or lease release.

#### Scenario: Provider constructs a runtime
- **WHEN** reconciliation needs a provider to construct a runtime
- **THEN** the registry SHALL record the pending entry generation before releasing its lock
- **AND** it SHALL invoke the provider outside the lock
- **AND** it SHALL install the result only if that definition generation remains current

#### Scenario: Runtime preparation reenters registry observation
- **WHEN** a runtime preparation callback synchronously or asynchronously observes registry state
- **THEN** the observation SHALL complete without deadlocking on a lock held across that callback
- **AND** unrelated registry entries SHALL remain accessible

#### Scenario: Callback completes after entry changes
- **WHEN** a provider or runtime callback completes after its definition was replaced, removed, or made unavailable
- **THEN** the registry SHALL NOT publish the stale result into the current entry
- **AND** it SHALL retire or close any stale runtime result outside the registry lock and according to committed-lease ordering

#### Scenario: Callback throws
- **WHEN** a provider or runtime callback throws a non-cancellation failure
- **THEN** the registry SHALL normalize it to the appropriate typed unavailable or failed state after reacquiring synchronization
- **AND** it SHALL leave unrelated entries and registry progress operational

### Requirement: Runtime generations own durable run authorization
The runtime registry SHALL assign a distinct generation identity to each live runtime instance and SHALL register every durable channel run with its channel instance ID, owning generation, and stable run identity. Completion of a transient PTT target SHALL release only that target's lease; it SHALL NOT implicitly complete or cancel an accepted durable run. Durable work MAY continue after the PTT callback returns, but every remote, tool, synthesis, playback, publication, and cancellation effect SHALL carry the owning generation authorization.

#### Scenario: PTT target releases while run continues
- **WHEN** a runtime accepts a user turn and its PTT target reaches its terminal callback before remote processing finishes
- **THEN** the registry SHALL release the transient target lease exactly once
- **AND** it SHALL retain the durable run owner and permit host-owned processing to continue
- **AND** no route, recorder, or PTT callback resource SHALL remain leased by the durable run

#### Scenario: Runtime is replaced for the same instance
- **WHEN** reconciliation replaces runtime generation G with generation H for one channel instance
- **THEN** new durable runs SHALL be owned by H
- **AND** runs already owned by G SHALL NOT be transferred implicitly to H
- **AND** G's outstanding effects SHALL be revoked or recovered only through the explicit durable-run contract

#### Scenario: Runs for different instances coexist
- **WHEN** durable runs are registered for two channel instances
- **THEN** each run SHALL retain its own instance and generation ownership
- **AND** completion, cancellation, queueing, or failure for one instance SHALL NOT mutate the other instance's runs

### Requirement: Runtime restart creates a fresh generation boundary
On service or process restart, the registry SHALL close and invalidate every prior runtime generation and SHALL allocate fresh generation identities for reconstructed runtimes. Persisted durable run and message records MAY be reconciled only through an explicit host recovery operation under a fresh generation; they SHALL NOT authorize callbacks from the prior process or automatically restore volatile conversation context. A late completion bearing a prior generation SHALL be stale regardless of its run status.

#### Scenario: Service restarts with a nonterminal run
- **WHEN** the service restarts while a durable run is queued, running, waiting for a tool, synthesizing, or pending playback
- **THEN** the registry SHALL reject callbacks carrying the old generation identity
- **AND** the host SHALL retain the persisted run and message state required by the durable-run contract
- **AND** any recovery under the new generation SHALL be explicit and SHALL NOT import the old volatile conversation as runtime context

#### Scenario: Old completion arrives after restart
- **WHEN** a network, tool, synthesis, or playback completion from the old process arrives after a new generation is installed
- **THEN** the registry SHALL classify it as stale or cancelled
- **AND** it SHALL NOT publish a response, play audio, execute a tool, advance a current run, or mutate current runtime status

#### Scenario: Fresh runtime starts after restart
- **WHEN** the host reconstructs a channel runtime after restart
- **THEN** the registry SHALL give it a generation identity distinct from every prior generation
- **AND** capability leases and durable run authorizations issued to the fresh runtime SHALL be distinct from all prior leases and authorizations

### Requirement: Durable run shutdown and recovery are separate from transient target closure
The registry SHALL distinguish transient committed input-target leases from durable run records. Runtime replacement, removal, or shutdown SHALL prevent new transient preparation, complete the existing target-lease ordering, and revoke active generation-bound effects without deleting durable records required for terminal-state, pending-response, or exact-once recovery. A later recovery SHALL use host-owned durable state and SHALL NOT invoke a closed runtime or replay an ambiguous external effect.

#### Scenario: Runtime is removed while a run is active
- **WHEN** a channel instance is removed while its PTT target is released but its durable run is still active
- **THEN** the registry SHALL prevent new input preparation and revoke the removed generation's live effects
- **AND** it SHALL preserve the durable run's terminal or recovery record according to the durable-run contract
- **AND** it SHALL NOT deliver late output to a replacement or unrelated channel instance

#### Scenario: Shutdown interrupts an external effect
- **WHEN** service shutdown interrupts a network request, tool call, synthesis operation, or playback admission
- **THEN** the registry SHALL cancel or detach the active effect according to its semantic contract
- **AND** it SHALL persist a terminal, indeterminate, or recoverable state without automatically replaying an effect whose delivery is ambiguous

#### Scenario: Recovery claims a persisted run
- **WHEN** a fresh runtime generation explicitly claims a persisted nonterminal run
- **THEN** the registry SHALL record the new generation owner before allowing callbacks
- **AND** callbacks from any previous owner SHALL remain stale
- **AND** the claim SHALL NOT reconstruct volatile conversation context unless a separate contract explicitly supplies it

### Requirement: Late durable completions cannot affect current registry state
Every durable callback SHALL include a run identity and generation authorization. The registry SHALL commit a completion, status, publication, playback admission, or tool result only when that authorization belongs to the current owner and the run has not reached a terminal state. Stale, duplicate, cancelled, and out-of-order callbacks SHALL be normalized without invoking provider or runtime code under the registry lock.

#### Scenario: Completion arrives after run cancellation
- **WHEN** a cancelled run later receives a remote, tool, synthesis, or playback completion
- **THEN** the registry SHALL ignore or classify the completion as stale
- **AND** it SHALL NOT publish, play, execute, or mark the cancelled run as successful

#### Scenario: Duplicate terminal completion arrives
- **WHEN** a durable run receives two terminal callbacks for the same stage
- **THEN** the registry SHALL commit at most one terminal transition
- **AND** it SHALL NOT duplicate a message, tool result, playback effect, or pending/heard transition

#### Scenario: Completion callback reenters the registry
- **WHEN** a durable completion callback observes or updates registry state synchronously or asynchronously
- **THEN** the registry SHALL release internal synchronization before invoking the callback
- **AND** it SHALL commit the callback result only if the run generation remains current
- **AND** unrelated registry lookup, projection, reconciliation, and lease release SHALL remain available

### Requirement: Durable registry boundaries remain language-neutral
Runtime-generation identities, durable run ownership, recovery claims, callback envelopes, and stale-effect outcomes SHALL be represented by language-neutral host-domain values and opaque handles. The registry SHALL NOT expose Kotlin coroutine jobs, Android callbacks, SDK futures, transport clients, audio routes, or a Lua-specific runtime ABI to providers or runtimes.

#### Scenario: Future language runtime owns a generation
- **WHEN** a future language adapter constructs a runtime and registers a durable run through the provider-neutral boundary
- **THEN** the registry SHALL apply the same generation, lease, recovery, and late-effect rules
- **AND** the adapter SHALL NOT need access to Kotlin, Android, SDK, or transport objects

#### Scenario: Registry implementation changes
- **WHEN** the host changes its coroutine, persistence, networking, or SDK implementation
- **THEN** generation and durable-run semantic contracts SHALL remain stable
- **AND** no implementation object SHALL become part of persisted run records or provider contracts

### Requirement: Host-owned actor readiness and failure latch are projected
For a runtime that exposes an actor scheduling boundary, the host SHALL own a per-generation readiness and fatal-failure latch projected through the registry entry. The host SHALL publish ready only after protected startup reports a normalized ready outcome; a startup coroutine suspended on an operation SHALL remain initializing until it resumes and reports ready. Startup, lifecycle-critical, instruction, memory, ownership-integrity, or another generation-fatal outcome SHALL latch failed. An ordinary event, background-task, or host-operation failure SHALL remain local unless explicitly escalated as generation-fatal. Neither latch SHALL be persisted or restored across a service or process restart.

#### Scenario: Actor becomes ready
- **WHEN** an actor's protected startup reports a normalized ready outcome
- **THEN** the host SHALL publish the registry entry as ready through the host-owned latch
- **AND** selection and projection SHALL observe that readiness without querying the in-actor scheduler

#### Scenario: Startup suspends
- **WHEN** an actor's startup coroutine yields an operation before reporting ready
- **THEN** the host SHALL keep the registry entry in its initializing state
- **AND** the actor SHALL NOT admit ordinary events or background work until startup resumes and reports ready

#### Scenario: Actor reaches a fatal failure
- **WHEN** startup, lifecycle-critical execution, instruction policy, memory policy, or ownership integrity reaches a generation-fatal outcome
- **THEN** the host SHALL latch the registry entry as failed
- **AND** a later in-actor resumption SHALL NOT clear the latch

#### Scenario: Ordinary operation fails locally
- **WHEN** an ordinary event, background task, or yielded host operation reports a normalized non-fatal failure
- **THEN** the host SHALL report that failure to its owning event, task, or coroutine
- **AND** it SHALL NOT latch the actor failed solely because of that local failure

#### Scenario: Service restarts after readiness or failure
- **WHEN** the service or process restarts after an actor was ready or failed
- **THEN** the registry SHALL create a fresh generation with fresh readiness and failure latches
- **AND** it SHALL NOT restore the prior generation's readiness, failure latch, suspended coroutines, mailbox entries, or operation tokens

### Requirement: Actor scope authorization is generation-scoped
For a runtime that exposes an actor scheduling boundary, every coroutine resumption, operation-token completion, descendant task, and capability invocation SHALL carry the generation authorization of the runtime that owns it. The host SHALL reject any descendant, resumption, or capability effect whose generation authorization is not current for the owning channel instance. A successor MAY receive an internal identity sufficient to create its state and load or validate source while staged, but the host SHALL NOT issue its event-admission or effect authorization before the predecessor has closed. A predecessor's outstanding authorizations SHALL be revoked before close. Authorizations SHALL be opaque host-domain values and SHALL NOT expose Lua state references, coroutine pointers, or in-actor scheduler objects to the registry, capabilities, or persisted records.

#### Scenario: Resumption carries current generation authorization
- **WHEN** an operation-token completion resumes a suspended coroutine on an actor runtime
- **THEN** the completion SHALL carry the generation authorization of the owning runtime
- **AND** the host SHALL reject it if that generation is no longer current for the channel instance

#### Scenario: Capability invocation is generation-scoped
- **WHEN** an actor descendant invokes a host capability
- **THEN** the capability boundary SHALL require a current generation authorization
- **AND** it SHALL reject invocations whose authorization belongs to a closed or superseded generation

#### Scenario: Successor effect authorization is withheld until predecessor closes
- **WHEN** a successor generation is staged while the predecessor has not closed
- **THEN** the successor MAY create state and load or validate source without running plugin startup or effects
- **AND** the host SHALL NOT issue successor event-admission or effect authorization until the predecessor has closed
- **AND** the predecessor's outstanding authorizations SHALL be revoked before its close completes

#### Scenario: Authorization remains opaque
- **WHEN** a generation authorization crosses the registry, capability, or persistence boundary
- **THEN** it SHALL be an opaque host-domain value
- **AND** it SHALL NOT expose a Lua state reference, coroutine pointer, or in-actor scheduler object

### Requirement: Fresh-generation recovery does not persist volatile coroutine state
On service or process restart, a reconstructed actor runtime SHALL start from a fresh generation and SHALL NOT restore suspended coroutines, mailbox entries, operation tokens, ready-queue positions, or in-actor scheduler state from the prior process. Persisted durable host records, durable run records, and durable messages MAY be reconciled through an explicit host recovery operation under the fresh generation, but volatile actor state SHALL NOT be persisted or replayed. A late completion bearing a prior generation's operation-token authorization SHALL be classified as stale regardless of its run status, and SHALL NOT resume a coroutine in the fresh generation.

#### Scenario: Restart discards volatile actor state
- **WHEN** the service restarts while an actor runtime has suspended coroutines, pending mailbox entries, or outstanding operation tokens
- **THEN** the fresh generation SHALL NOT restore any suspended coroutine, mailbox entry, operation token, or ready-queue position
- **AND** the fresh actor SHALL reconstruct its work only through an explicit host recovery operation using durable host records

#### Scenario: Late operation completion after restart
- **WHEN** an operation-token completion from the prior generation arrives after a fresh actor generation is installed
- **THEN** the registry SHALL classify it as stale
- **AND** it SHALL NOT resume a coroutine in the fresh generation
- **AND** it SHALL NOT publish an effect, mutate readiness, or advance a current run

#### Scenario: Durable recovery is explicit and fresh
- **WHEN** the host recovers durable run and message records into a fresh actor generation
- **THEN** the recovery SHALL use host-owned durable state under the fresh generation authorization
- **AND** it SHALL NOT import volatile conversation context or actor scheduler state from the prior process

### Requirement: Provider revision changes participate in runtime reconciliation
The runtime registry SHALL associate every available entry with the stable implementation identifier and opaque provider-revision fingerprint that constructed its current generation. Reconciliation SHALL compare both the effective channel definition and resolved provider revision. Provider addition SHALL allow a formerly missing definition to construct; publication of the same provider fingerprint SHALL NOT replace an unchanged runtime; update or rollback to another fingerprint SHALL replace every affected enabled instance through a fresh generation; provider removal, corruption, incompatibility, or failed materialization SHALL retire affected generations and preserve explicit unavailable entries. Provider revision fingerprints SHALL remain provider-level state and SHALL NOT be persisted in channel definitions.

#### Scenario: Missing installed provider becomes available
- **WHEN** the current catalogue preserves a definition for a missing installed provider and a complete provider snapshot later publishes that provider
- **THEN** reconciliation SHALL validate the definition through the provider and construct a fresh runtime generation when valid
- **AND** it SHALL NOT require a catalogue mutation or package reference in instance configuration

#### Scenario: Same provider revision is republished
- **WHEN** the effective channel definition is unchanged and provider resolution returns the same implementation ID and revision fingerprint
- **THEN** reconciliation SHALL retain the current runtime generation
- **AND** it SHALL NOT rerun Lua startup or create another actor or state solely because a snapshot was republished

#### Scenario: Installed provider revision changes
- **WHEN** update or explicit rollback publishes a different provider-revision fingerprint for an implementation ID referenced by enabled definitions
- **THEN** reconciliation SHALL construct a fresh successor generation for each affected instance
- **AND** each successor SHALL load only the newly active immutable program image
- **AND** instances using other providers SHALL retain their existing generations

#### Scenario: Installed provider is removed or becomes invalid
- **WHEN** provider resolution no longer supplies a usable revision for an existing package-backed definition
- **THEN** the registry SHALL retire and close the old generation through its ordinary lifecycle
- **AND** it SHALL preserve an unavailable entry at the same catalogue position with a typed package or provider reason
- **AND** it SHALL preserve the definition and opaque configuration unchanged

### Requirement: Package-driven replacement retains drain-before-ready ordering
An installed-provider revision replacement SHALL use the existing generation replacement boundary. Before successor generation H may execute Lua startup, receive event/effect authorization, publish readiness, or accept input, predecessor generation G SHALL stop new admission, complete any committed input terminal callback exactly once, cancel or drain descendants and outstanding effects, revoke generation-scoped capabilities, and close exactly once. A package update or rollback SHALL NOT create a parallel hot-swap path, mutate source inside G, transfer G's Lua state to H, or treat a timeout as permission to expose both revisions live.

#### Scenario: Update occurs during committed input
- **WHEN** package update changes the provider revision while generation G owns a committed input target
- **THEN** G's target SHALL receive and complete its terminal callback under G
- **AND** H SHALL NOT start or become ready until G's target lease, descendants, effects, capabilities, actor, and gate have drained or closed in the established order

#### Scenario: Old completion arrives after package replacement
- **WHEN** a timer, task, callback, or host-operation completion from revision G arrives after H becomes current
- **THEN** the host SHALL reject it as stale or closed without entering H's Lua state
- **AND** it SHALL not mutate H's logs, readiness, globals, module cache, status, or effects

#### Scenario: Replacement source fails before successor readiness
- **WHEN** the newly active provider revision cannot construct or activate generation H after G retires
- **THEN** the instance SHALL become unavailable or failed with the typed new-revision reason
- **AND** the registry SHALL NOT restart G or automatically select the package rollback revision

### Requirement: Provider removal and recovery preserve durable definitions but not volatile runtime state
When an installed provider is removed, corrupt, incompatible, or globally unavailable, the registry SHALL preserve every catalogue definition and generic ordered projection while closing any live generation. If the same durable provider identity later becomes available through explicit install, update, or rollback, reconciliation SHALL construct a fresh generation from the then-active exact artifact. It SHALL NOT restore prior Lua globals, module caches, timers, suspended coroutines, operation tokens, readiness/failure latches, actor queues, logs, or old-generation authorization.

#### Scenario: Removed provider is reinstalled
- **WHEN** a previously removed durable provider identity is explicitly installed again and its package becomes available
- **THEN** existing catalogue definitions referencing that identity SHALL be eligible for fresh runtime construction
- **AND** their instance IDs, names, order, enabled state, schema version, and opaque configuration SHALL remain unchanged

#### Scenario: Provider recovers after process restart
- **WHEN** a valid committed package is materialized after restart for a preserved definition
- **THEN** the registry SHALL allocate a fresh runtime generation and capability authorization
- **AND** no prior-process actor or completion SHALL be accepted as current

### Requirement: Installed-provider failures are isolated across providers and built-ins
A package parse, integrity, compatibility, provider-materialization, runtime-construction, activation, update, rollback, or removal failure for one provider SHALL NOT remove, replace, close, or make unavailable a runtime belonging to another provider. A globally unavailable installed-package index SHALL affect only installed-provider resolution; immutable built-in providers and their catalogue instances SHALL remain operational.

#### Scenario: One installed provider is corrupt
- **WHEN** provider A cannot materialize because its active artifact is corrupt while installed provider B and built-in providers are valid
- **THEN** only A's definitions SHALL project the corresponding unavailable state
- **AND** B and built-in runtime generations SHALL remain available and unchanged

#### Scenario: Installed index cannot be recovered
- **WHEN** no complete valid installed-package index generation can be selected
- **THEN** installed-provider definitions SHALL project a typed package-store unavailable reason
- **AND** built-in providers SHALL continue to resolve, construct, prepare input, and close normally
