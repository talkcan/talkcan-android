## Purpose

TBD. Defines the embedded Lua 5.4 runtime substrate proof behavior for Android targets, including native build, state isolation, protected execution, coroutine suspension, cancellation, allocation accounting, thread topology, evidence recording, substrate disposition selection, and non-interference with existing channel behavior.

## Requirements

### Requirement: The proof embeds pinned source-only Lua 5.4 on the supported Android target
The system SHALL embed one pinned Lua 5.4.8 patch release for Android API 31+ and `arm64-v8a` as prebuilt third-party native libraries distributed inside a vendored AAR (`party.iroiro.luajava:lua54`), SHALL execute proof chunks from Lua source, and SHALL NOT build the Lua interpreter from first-party native sources for this component. The embedding SHALL NOT expose plugin-provided bytecode, `package.loadlib`, C-module searchers, JNI, FFI, or plugin-provided shared-library loading.

#### Scenario: Android proof library loads
- **WHEN** the debug and release proof builds run on the supported Android architecture
- **THEN** the application SHALL load the pinned Lua runtime from the vendored AAR natives and execute a source chunk through the internal proof bridge
- **AND** the proof SHALL report the exact Lua version, the AAR version, and its recorded artifact hash

#### Scenario: Binary or native module loading is requested
- **WHEN** a proof chunk attempts to load a binary chunk or dynamically load a native module
- **THEN** the bridge SHALL reject the operation without executing the supplied binary or shared library
- **AND** the Lua state and Android process SHALL remain usable

### Requirement: Lua states are independent and accessed through opaque ownership
The proof SHALL create independent Lua states whose globals, module caches, coroutines, and lifecycle are not observably shared. Kotlin SHALL address states, coroutines, and suspended operations only through opaque identifiers validated against their owning state generation.

#### Scenario: Two states mutate equal global names
- **WHEN** two proof states assign different values to the same Lua global and load package-local modules
- **THEN** each state SHALL observe only its own value and module cache
- **AND** closing either state SHALL NOT change the surviving state's values or usability

#### Scenario: Handle belongs to another state
- **WHEN** Kotlin submits a coroutine or operation handle to a state that does not own it
- **THEN** the bridge SHALL return a typed invalid-owner or stale outcome
- **AND** it SHALL NOT enter or mutate either Lua state

### Requirement: Every JVM-to-Lua execution path is protected
Source loading, entrypoint execution, callback invocation, coroutine resume, error normalization, and any close helper that executes Lua SHALL run through protected Lua execution or an equivalent binding guarantee. Expected syntax, runtime, cancellation, quota, invalid-handle, and lifecycle failures SHALL return normalized outcomes and SHALL NOT reach Lua's process-aborting panic path or a JVM process-aborting failure.

#### Scenario: Lua source has a syntax error
- **WHEN** the proof loads malformed Lua source
- **THEN** the bridge SHALL return a normalized syntax failure
- **AND** the state SHALL remain closable
- **AND** another state SHALL remain usable

#### Scenario: Lua callback raises an error
- **WHEN** an invoked Lua callback raises a string or non-string error object
- **THEN** the bridge SHALL return a bounded normalized runtime failure with diagnostic context
- **AND** the Android process SHALL remain alive
- **AND** no native or JVM failure SHALL escape the bridge

### Requirement: Coroutine suspension and resumption cross the bridge exactly once
The proof SHALL allow a protected Lua callback to yield a host-operation token and suspend its execution inside a blocking round-trip on the state's dedicated engine thread: the engine thread posts a typed host-operation request, blocks awaiting a normalized completion, and delivers that completion into the suspended coroutine exactly once. Suspension SHALL NOT execute Lua instructions while awaiting completion. Each admitted operation token SHALL accept at most one terminal resume, cancellation, or close outcome.

#### Scenario: Suspended operation completes
- **WHEN** a Lua callback yields for a host operation and Kotlin completes the owning token while the state is live
- **THEN** the bridge SHALL deliver the supplied normalized result into the owning coroutine exactly once
- **AND** the callback SHALL complete without executing Lua instructions during the suspension interval

#### Scenario: Completion is delivered twice
- **WHEN** Kotlin submits two terminal completions for the same operation token
- **THEN** at most one completion SHALL resume Lua
- **AND** the duplicate SHALL return an already-completed or stale outcome without another Lua effect

### Requirement: Cancellation and state closure suppress late continuations
The proof SHALL support cancellation of suspended operations and idempotent closure of a Lua state. Closing a state SHALL invalidate all owned coroutine and operation handles, SHALL prevent later continuation delivery, and SHALL release native state ownership deterministically from the bridge caller's perspective.

#### Scenario: Completion races cancellation
- **WHEN** cancellation and completion race for one suspended operation
- **THEN** exactly one terminal outcome SHALL win
- **AND** Lua SHALL be resumed at most once
- **AND** the losing request SHALL observe a cancelled, completed, or stale terminal result

#### Scenario: Completion arrives after state close
- **WHEN** a state closes while an operation is suspended and its completion arrives later
- **THEN** the bridge SHALL reject the completion as closed or stale
- **AND** it SHALL NOT access freed Lua state or coroutine memory
- **AND** repeated close requests SHALL remain idempotent

### Requirement: Uncooperative pure-Lua execution is interrupted within a measured finite bound
The proof SHALL install, from trusted bootstrap code loaded before any package code, an instruction-count hook that observes a host-settable watchdog flag and interrupts pure-Lua execution which exceeds the configured proof budget. The hook SHALL be installed on the main execution context and on every coroutine created through the bootstrap's overridden `coroutine.create`/`coroutine.wrap`, and the `debug` library SHALL remain unavailable to package code so packages cannot remove the hook. Interruption SHALL normalize the affected state as failed or closing, SHALL permit deterministic state teardown, and SHALL leave unrelated states usable. The proof SHALL NOT claim that this mechanism interrupts a blocking native host function.

#### Scenario: Lua executes an infinite loop
- **WHEN** a proof callback enters an infinite pure-Lua loop
- **THEN** the bridge SHALL interrupt it within a recorded finite instruction and elapsed-time bound
- **AND** the affected state SHALL close without relying on cooperative Lua return
- **AND** another proof state SHALL continue executing

#### Scenario: Package code attempts to remove the interrupt hook
- **WHEN** package code attempts to access `debug.sethook` or replace the installed hook
- **THEN** the runtime SHALL deny the access because `debug` is unavailable to package environments
- **AND** the interrupt hook SHALL remain installed

#### Scenario: Host operation would block
- **WHEN** a proof host operation represents work that cannot complete in a deliberately small bounded native call
- **THEN** the bridge SHALL yield a host-operation token before that work executes
- **AND** it SHALL NOT block the Lua execution thread waiting for external completion

### Requirement: Lua state entry is serialized and thread topology is evidenced
The bridge SHALL ensure that each Lua state is owned by exactly one dedicated engine thread for its entire lifetime and is never entered from any other thread. All interaction between the engine thread and the rest of the application SHALL occur through typed messages; host operations SHALL be expressed as blocking message round-trips executed on the engine thread. The proof SHALL exercise this confined thread-per-state topology with multiple independent states, coroutine resumptions, interruption, cancellation, and closure, and SHALL record the evidence used for selection.

#### Scenario: Concurrent callers target one state
- **WHEN** multiple JVM callers concurrently request entry to the same proof state
- **THEN** the bridge SHALL queue those requests on the state's dedicated engine thread according to one recorded ordering rule
- **AND** no two callers SHALL execute Lua instructions in that state concurrently
- **AND** no caller other than the engine thread SHALL ever touch the Lua state

#### Scenario: Independent states execute under the candidate topology
- **WHEN** several proof states repeatedly execute, yield, resume, and close under the confined thread-per-state topology
- **THEN** each state SHALL preserve its ownership and continuation invariants
- **AND** the proof SHALL record the topology, thread count, latency, and failures used for selection

### Requirement: The proof records reproducible correctness and device evidence
The change SHALL record the pinned Lua version, the vendored AAR version and its artifact hash, build type, target device, experiment parameters, correctness outcomes, startup and close latency, continuation latency, interruption latency, interrupt-hook interval and measured overhead, multi-state behavior, and cancellation/close race results. Numeric observations SHALL be evidence for later policy and SHALL NOT become a public plugin compatibility promise in this change.

#### Scenario: Candidate completes the experiment suite
- **WHEN** a bridge candidate completes host and Android instrumentation experiments
- **THEN** the recorded evidence SHALL identify every passed and failed correctness gate
- **AND** it SHALL contain enough version and parameter information to rerun the experiments

#### Scenario: Numeric measurement varies across runs
- **WHEN** device measurements differ between executions
- **THEN** the evidence SHALL preserve the observed range or distribution and test conditions
- **AND** the change SHALL NOT silently convert one observed value into a normative plugin limit

### Requirement: One substrate disposition is selected and experimental alternatives are removed
The completed change SHALL select the Kotlin actor kernel embedding pinned prebuilt Lua 5.4.8 natives through the vendored AAR, with one dedicated confined engine thread per state and message-passing host operations, as the substrate disposition after correctness gates pass. Losing experimental code — the first-party Rust cdylib kernel, its JNI exports, its Kotlin JNI bridge layer, and its Gradle native-build wiring — SHALL NOT remain active. If no candidate passes, the completed evidence SHALL record the Kotlin kernel as not yet viable and SHALL identify the engine, bridge, or topology question for a follow-up.

#### Scenario: One or more candidates pass every correctness gate
- **WHEN** viable candidates have complete evidence
- **THEN** the design SHALL record one selected topology and the reasons alternatives were rejected
- **AND** the repository SHALL retain only the selected internal Kotlin kernel, its vendored AAR dependency, and its conformance coverage

#### Scenario: No candidate passes every correctness gate
- **WHEN** every candidate violates at least one required correctness or containment property
- **THEN** the change SHALL record a negative substrate disposition with the failing gates
- **AND** it SHALL NOT introduce a production Lua runtime or silently weaken the requirements

### Requirement: The substrate proof does not alter current channel behavior
The proof bridge and harness SHALL remain internal and non-user-visible. This change SHALL NOT register a Lua provider, load third-party packages, expose a public Lua ABI, alter persisted channel definitions, or change existing channel routing, PTT, runtime-registry, capability, audio, UI, foreground-service, or release behavior.

#### Scenario: Application runs without invoking proof instrumentation
- **WHEN** the application starts and uses existing Kotlin channels normally
- **THEN** no Lua state SHALL be created by ordinary production channel startup
- **AND** existing channel and service behavior SHALL remain unchanged

#### Scenario: Proof change is rolled back
- **WHEN** the internal bridge, dependency, build wiring, and proof harness are removed
- **THEN** no persisted-data migration or user-visible recovery action SHALL be required