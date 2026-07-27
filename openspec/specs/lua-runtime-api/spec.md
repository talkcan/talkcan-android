## Purpose

Modifies Lua Runtime API value-normalization and context-restricted callback yielding rules to support opaque audio userdata and yield-capable input callbacks.
## Requirements
### Requirement: Lua runtime is versioned and presents a stable compatibility contract detectable before state creation
The system SHALL embed source-only Lua 5.4.8 as the Talkcan Lua Runtime v1 language environment. The runtime SHALL expose the Lua language version as the exact string `"Lua 5.4"`, the Lua release as the exact string `"5.4.8"`, and the Talkcan Lua Runtime API version as `"talkcan-lua-v1"` through `talkcan.runtime` constants. There SHALL be no `version()` function, no integer runtime version, and no integer API version.

A program image SHALL declare exactly two execution requirements via the fields `luaVersion` and `apiVersion`. These SHALL be the only execution-requirement fields; the host SHALL NOT interpret additional fields as compatibility constraints. Before creating a Lua state, the host SHALL compare the image's `luaVersion` against the host's `LUA_VERSION` and the image's `apiVersion` against the host's `API_VERSION` by exact string equality. No range matching, ordering, or fuzzy comparison SHALL be performed. If either field is not equal, the host SHALL produce a typed compatibility-failure outcome without creating a Lua state or loading any source.

#### Scenario: Runtime reports version constants
- **WHEN** the host queries the runtime for version information
- **THEN** the runtime SHALL expose `talkcan.runtime.LUA_VERSION` as `"Lua 5.4"`, `talkcan.runtime.LUA_RELEASE` as `"5.4.8"`, and `talkcan.runtime.API_VERSION` as `"talkcan-lua-v1"`

#### Scenario: Program requires incompatible API version
- **WHEN** a program image declares an `apiVersion` not equal to the host's `API_VERSION`
- **THEN** the host SHALL return a typed compatibility-failure outcome before creating a Lua state or loading any source
- **AND** the containing channel instance SHALL project an explicit unavailable state with the typed compatibility reason

#### Scenario: Program requires compatible versions
- **WHEN** a program image declares `luaVersion` equal to the host's `LUA_VERSION` and `apiVersion` equal to the host's `API_VERSION`
- **THEN** the host SHALL proceed to source-map validation and SHALL create a Lua state and load the entry module only if that validation succeeds

#### Scenario: Program image contains additional fields beyond execution requirements
- **WHEN** a program image declares fields beyond `luaVersion` and `apiVersion`
- **THEN** the host SHALL ignore non-execution-requirement fields
- **AND** it SHALL NOT interpret them as compatibility, identity, package, or version constraints
- **AND** it SHALL NOT use them for ordering, fallback, or provider selection

### Requirement: Lua execution is source-only
The runtime SHALL accept only Lua source text and SHALL reject binary bytecode chunks, precompiled Lua libraries, C modules loaded through `package.loadlib` or `require`, JNI calls, FFI calls, and plugin-provided shared-library loading. The runtime SHALL also disable the `string.dump` function to prevent bytecode production. Rejection SHALL return a typed error to the calling Lua code under protected execution and SHALL NOT affect other running actors, the Lua state's closability, or the Android process.

#### Scenario: Bytecode chunk is loaded
- **WHEN** a program chunk or any loaded module attempts to load a binary bytecode string via `load` or `loadfile` with a non-text signature
- **THEN** the runtime SHALL reject the operation without executing the supplied binary
- **AND** the owning actor SHALL remain usable and closable

#### Scenario: C module searcher is accessed
- **WHEN** a Lua program references a C module through `package.searchers` or reads any field of a `package` global
- **THEN** the `package` global SHALL be entirely absent (not nil-configured, literally missing)
- **AND** an attempt to read `package`, `package.searchers`, `package.path`, `package.cpath`, or `package.loadlib` SHALL return `nil` (standard Lua default for absent globals)
- **AND** `require` SHALL never invoke a native shared-library loader

#### Scenario: Plugin calls string.dump
- **WHEN** a callback attempts to call `string.dump`
- **THEN** the runtime SHALL raise an error that `string.dump` is disabled
- **AND** it SHALL NOT produce a bytecode string

### Requirement: Host `talkcan.*` modules are reserved and injected; package-local `require` resolves only non-reserved names
The runtime SHALL inject the public module names `talkcan.runtime`, `talkcan.channel`, and `talkcan.log` and SHALL reserve those names against any source-map entry or plugin override. The exact module name `talkcan` and every name beginning with `talkcan.` SHALL be reserved; no source-map entry SHALL use them. The host source map SHALL NOT contain entries whose module name begins with `talkcan.` and SHALL NOT contain an entry named exactly `talkcan`; any such entry SHALL be rejected at the source-map validation boundary.

Package-local module names SHALL match the canonical grammar `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*`; names containing empty segments, trailing dots, leading digits, uppercase letters, or path separators SHALL be rejected at the source-map validation boundary. The replacement `require` function SHALL use a fixed three-step resolution order: (1) reserved host-module check — if the name begins with `talkcan.`, look it up in the host's preloaded module table and never consult the source map; if the name is a reserved prefix but not a known module, raise `E_RESERVED_MODULE`; (2) per-generation module cache — return a previously loaded module; (3) immutable source-map lookup — load the source in a new Lua closure sharing the actor's restricted global environment, execute it, cache the result, and return it; if not found, raise `E_MODULE_NOT_FOUND`. The `require` function SHALL never return `nil` with a string error; every resolution either returns a non-nil module value or raises a protected table error catchable via `pcall`. When a loaded module returns `nil`, the runtime SHALL cache and return `true` as the module value. When a loaded module returns a non-nil value, the runtime SHALL cache and return that value. A recursive `require` cycle (a module whose execution re-enters `require` for a module currently being loaded in the same resolution chain) SHALL be detected with bounded resolution and SHALL raise `E_MODULE_CYCLE`; the runtime SHALL NOT recurse unboundedly or stack-overflow. An invalid module name (path separator, invalid characters, grammar violation) SHALL raise `E_INVALID_MODULE_NAME`. All resolved modules SHALL be cached per Lua state; two states SHALL NOT share module caches. Every source-map module closure SHALL share the same restricted global environment as the entry module; isolation SHALL be provided by the per-generation Lua state and the sandboxed global table, not by per-module environments.
Every source-map module chunk evaluation — whether the entry module or any module loaded lazily via `require` — SHALL be synchronous and non-yielding. During module chunk evaluation the chunk MAY call `require` (to read constants or load helper modules), read global constants, construct tables, define functions, and return values. The chunk MUST NOT invoke any host-provided effectful callable — including `talkcan.runtime.spawn`, `talkcan.runtime.sleep`, `talkcan.log.info` (and all other log levels), or any other call on a host-injected module — during its top-level evaluation. Attempted call of a host-provided effectful function during module chunk evaluation SHALL fail the module load with a typed effect-call-during-load error and SHALL NOT cache a partial result. Host-provided effectful functions SHALL be invoked only from returned callback functions executing in the authorized `startup`, event callback, or background task contexts.

#### Scenario: Plugin requires a host-injected talkcan module
- **WHEN** a program chunk executes `require("talkcan.runtime")`
- **THEN** the runtime SHALL return the host-injected module table without consulting the source map
- **AND** the plugin SHALL NOT be able to replace or override the injected module by providing source with that name

#### Scenario: Plugin requires a talkcan name absent from the host injection set
- **WHEN** a program chunk executes `require("talkcan.unknown")`
- **THEN** the runtime SHALL raise `E_RESERVED_MODULE` (catchable via `pcall`)
- **AND** it SHALL NOT search the source map, filesystem, or network

#### Scenario: Source map contains a talkcan-prefixed entry
- **WHEN** the host source map includes an entry for `talkcan.scheduler`
- **THEN** the runtime SHALL reject that entry at the source-map validation boundary
- **AND** a subsequent `require("talkcan.scheduler")` SHALL raise `E_RESERVED_MODULE`

#### Scenario: Source-map entry uses an invalid package-local name
- **WHEN** the source map contains an entry with an empty segment such as `myplugin..helpers`, a trailing dot `myplugin.`, an uppercase letter `MyPlugin`, or a name beginning with a digit `2nd_helpers`
- **THEN** the runtime SHALL reject that entry at the source-map validation boundary
- **AND** `require` of that name SHALL raise `E_INVALID_MODULE_NAME`

#### Scenario: Plugin requires exact `talkcan` reserved name
- **WHEN** a program chunk executes `require("talkcan")`
- **THEN** the runtime SHALL raise `E_RESERVED_MODULE`
- **AND** it SHALL NOT search the source map, filesystem, or network

#### Scenario: Plugin requires a non-reserved package-local module
- **WHEN** a program chunk executes `require("myplugin.helpers")` and the name exists in the host source map
- **THEN** the runtime SHALL load the module source in a closure sharing the actor's restricted global environment, execute it, and cache the result in the requesting actor's Lua state
- **AND** subsequent `require` calls for the same name from that state SHALL return the cached module table

#### Scenario: Plugin requires a non-reserved package-local module that is absent
- **WHEN** a program chunk executes `require("missing.helper")` and the name does not exist in the host source map
- **THEN** the runtime SHALL raise `E_MODULE_NOT_FOUND` catchable via `pcall`
- **AND** it SHALL NOT return `nil` with a string message
- **AND** it SHALL NOT search the filesystem or network

#### Scenario: Module returns nil and require caches true
- **WHEN** a source-map module's top-level return statement is `return nil` or omits a return value
- **THEN** the runtime SHALL cache `true` as the module value for that name
- **AND** subsequent `require` calls for that name SHALL return `true`

#### Scenario: Module returns a non-nil value and require caches it
- **WHEN** a source-map module's top-level return statement returns a table, string, number, boolean, or function
- **THEN** the runtime SHALL cache the returned value as the module value for that name
- **AND** subsequent `require` calls for that name SHALL return the cached value

#### Scenario: Recursive require cycle raises E_MODULE_CYCLE
- **WHEN** module `a` requires module `b` and module `b` requires module `a` before either has completed loading, creating a resolution cycle
- **THEN** the runtime SHALL detect the cycle with bounded resolution
- **AND** it SHALL raise `E_MODULE_CYCLE` catchable via `pcall`
- **AND** it SHALL NOT recurse unboundedly or stack-overflow

#### Scenario: Module name contains a path separator or invalid characters
- **WHEN** a program chunk executes `require("../../etc/passwd")` or `require("my plugin")`
- **THEN** the runtime SHALL reject the name as invalid before any resolution attempt
- **AND** it SHALL raise `E_INVALID_MODULE_NAME`

#### Scenario: Two actors load the same package-local module independently
- **WHEN** two independent channel runtime actors both execute `require("myplugin.helpers")`
- **THEN** each actor SHALL receive its own module instance and cache
- **AND** mutation of one actor's cached module table SHALL NOT affect the other actor's module

### Requirement: Public values, errors, and cancellation are normalized Lua data values
All ordinary host-to-Lua and Lua-to-host data SHALL use `nil`, Boolean, finite number, valid-UTF-8 string, and plain map/contiguous-array tables, except for explicit state-local opaque Recording, Synthesized audio, Mount, JSON Null, SecretReference, Queue, Job, and work Effect userdata defined by public APIs. Mixed/sparse/invalid-key tables SHALL be rejected. Callback and method results SHALL follow exact contracts. Failures SHALL use `(nil, error_table)` with stable `error`; live cancellation SHALL use `E_CANCELLED`; generation/state close SHALL discard suspended coroutines without delivery.

Normalization SHALL enforce finite depth, entries, aggregate/string/key bytes, and supported-value rules. Cycles, non-approved metatables, functions, threads, platform objects, non-finite numbers, invalid UTF-8/shapes, and unsupported/foreign userdata SHALL reject the whole value. Ordinary serialization, logs, scalar configuration, callback terminals, errors, profile scalar fields, and durable work payload/effect results SHALL reject all opaque userdata unless that exact API explicitly consumes it. JSON Null is valid only in declared JSON paths. Lua references retained inside one state—including callbacks, module cache, `spawn`, `defer`, `job:effect`, and opaque-handle upvalues—are not normalized host data. Only `spawn`, `defer`, and `job:effect` SHALL accept function references; none serializes a function into public host data.

#### Scenario: Callback returns allowed value
- **WHEN** a protected callback returns a value permitted by its exact contract
- **THEN** the runtime SHALL interpret it without coercing it into another shape

#### Scenario: Callback returns declared failure
- **WHEN** a callback returns its exact application error
- **THEN** the runtime SHALL classify it as application failure and issue no further callback for that operation

#### Scenario: Live cancellation occurs
- **WHEN** an operation is cancelled while its owner remains resumable
- **THEN** it SHALL resume at most once with `E_CANCELLED`

#### Scenario: Platform object is returned
- **WHEN** host code attempts to deliver Kotlin/Android/URL/JNI/actor/platform state
- **THEN** normalization SHALL deny it with `E_INVALID_VALUE`

#### Scenario: Opaque value enters wrong path
- **WHEN** Lua logs, persists, configures, or returns audio, mount, secret reference, JSON null, queue, job, or effect outside an explicitly consuming API
- **THEN** the whole value SHALL be rejected with `E_INVALID_VALUE`
- **AND** no token, secret, or authority SHALL be inspected or stripped

#### Scenario: Invalid structural value is normalized
- **WHEN** output contains a cycle, invalid metatable, function, thread, unsupported userdata, non-finite number, or mixed/sparse table
- **THEN** the whole value SHALL be rejected with `E_INVALID_VALUE`
- **AND** no partial value SHALL cross the boundary

### Requirement: Host operations use validated contexts and normalized success/error pairs
Outside source-map module chunk evaluation, every public host operation available through the `talkcan.*` modules SHALL return two values to the caller: `(value, nil)` on success and `(nil, error_table)` on failure, where `error_table` is a table with at least an `error` string field and optionally a `reason` field. During entry or lazy-module evaluation, the module-loader effect guard defined above takes precedence: a host-call attempt fails the whole load with the typed effect-call-during-load outcome and does not return a normal success/error pair to continued chunk evaluation. A yielding operation such as `talkcan.runtime.sleep` or yielding audio operations SHALL suspend internally without exposing their operation tokens; a non-yielding admission operation such as `talkcan.runtime.spawn` SHALL return synchronously. Cancellation of a live operation SHALL produce `(nil, {error = "E_CANCELLED"})`. The plugin SHALL NOT receive an opaque operation token, coroutine reference, or host platform handle from any public host call; it SHALL receive only normalized values or host-constructed opaque audio userdata. Duplicate completions, completions after cancellation, and completions after close SHALL be rejected by the host without resuming Lua.

`talkcan.runtime.spawn` SHALL accept a single function argument. If the argument is not a function, the call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})` and SHALL NOT admit a task. Spawn is allowed only from the `startup` callback and from background tasks already executing in a runtime-managed spawned coroutine. Spawn from synchronous event callbacks (`handle_lifecycle`, `handle_input`, `handle_sos`, `handle_readiness`) or from plugin-created child coroutines SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` and SHALL NOT admit a task. Spawn attempted during entry or lazy-module evaluation SHALL instead fail that module evaluation with the typed effect-call-during-load outcome and SHALL NOT continue or cache a partial module result.

`talkcan.runtime.sleep` SHALL accept a single finite number argument >= 0. If the argument is negative, `NaN`, positive infinity, negative infinity, or above the host-configured maximum delay, the call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})` and SHALL NOT suspend the coroutine or start a timer. A value of 0 MAY be clamped to the host's minimum timer tick. Sleep is allowed only from runtime-managed spawned coroutines (the coroutine created for the background function passed to `spawn`). Sleep from synchronous event callbacks (`startup`, `handle_readiness`, `handle_lifecycle`, `handle_sos`), the yield-capable `handle_input` callback, or plugin-created child coroutines SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` and SHALL NOT suspend or start a timer. Sleep attempted during entry or lazy-module evaluation SHALL instead fail that module evaluation with the typed effect-call-during-load outcome and SHALL NOT continue or cache a partial module result. When the per-generation timer slot limit is reached, sleep SHALL return `(nil, {error = "E_BUSY"})` without suspension.

Managed background tasks admitted via `talkcan.runtime.spawn` MAY remain live until their function returns, fails, or the owning generation is closed. The host SHALL NOT terminate a task solely because a generic wall-clock lifetime has elapsed. The host SHALL bound the maximum number of concurrently managed tasks admitted per generation; exhaustion SHALL return `E_BUSY`. Suspended time — periods during which a background coroutine is suspended in `sleep` or awaiting a host operation — SHALL NOT be charged against active Lua execution limits; only active Lua execution slices remain subject to host-configured instruction-count and wall-clock bounds.

Each `sleep` call SHALL establish an operation-specific deadline computed as `requested_delay + bounded_slack`, where `bounded_slack` is a host-configured timer margin. If timer completion wins before that deadline, the host SHALL resume the coroutine with `(true, nil)`. If the deadline wins before timer completion, the host SHALL resume the coroutine exactly once with `(nil, {error = "E_TIMEOUT"})`; a later timer completion SHALL be rejected as stale and SHALL NOT resume Lua again. On generation close, the sleeping coroutine SHALL NOT be resumed and neither timeout, cancellation, nor any outcome SHALL be delivered.

The Lua function `coroutine.yield` SHALL remain available only for pure-Lua child-coroutine composition that is fully resumed inside the same active host callback or runtime-managed spawned task. It SHALL NOT become a host operation or suspend execution across the actor boundary. A raw yield escaping a callback or spawned-task boundary SHALL produce `E_INVALID_YIELD` and the owning callback/task failure defined for that boundary. By contrast, a call to `talkcan.runtime.sleep`, `talkcan.runtime.spawn`, or a semantic audio function from an ineligible loaded callback SHALL be rejected before yielding with that host function's ordinary `(nil, {error = "E_INVALID_CONTEXT"})` pair. `handle_input` is eligible only for semantic audio yielding; sleep and spawn remain context-denied.

#### Scenario: Sleep timer fires normally in a spawned task
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(1.0)` and the timer expires while the actor is live
- **THEN** the host SHALL internally suspend the coroutine, fire the timer, and resume the coroutine
- **AND** the sleep call SHALL return `(true, nil)` to the callback
- **AND** the spawned task SHALL NOT have received an operation token or handle at any point

#### Scenario: Sleep operation is cancelled while the actor is live
- **WHEN** a spawned background task is suspended in `talkcan.runtime.sleep` and that operation is cancelled before timer completion while the actor remains live
- **THEN** the host SHALL resume the coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_CANCELLED"})`

#### Scenario: Synchronous event callback calls context-restricted operation
- **WHEN** `startup`, `handle_readiness`, `handle_lifecycle`, or `handle_sos` calls `talkcan.runtime.sleep` or a semantic audio operation after module loading
- **THEN** the operation SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or host effect
- **AND** the denied call SHALL NOT by itself fail the callback if Lua handles or ignores the result
- **AND** an unrecognized raw `coroutine.yield` escaping the callback SHALL instead produce a typed callback-contract failure

#### Scenario: Input callback calls sleep or raw yield
- **WHEN** the host-managed `handle_input` callback calls `talkcan.runtime.sleep`
- **THEN** sleep SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` without suspending or starting a timer
- **AND** the denied call SHALL NOT by itself fail the callback if Lua handles or ignores the result
- **AND** an unrecognized raw `coroutine.yield` escaping the input callback SHALL instead produce a typed callback-contract failure

#### Scenario: Input callback yields on semantic audio operations
- **WHEN** the host-managed `handle_input` callback invokes a yielding semantic audio operation such as `talkcan.transcription.transcribe`
- **THEN** the host SHALL suspend the callback coroutine, execute the host operation, and resume the coroutine with the operation result when complete
- **AND** the actor's execution slot SHALL be released during the suspension

#### Scenario: Spawned task uses `coroutine.yield` for child-coroutine composition
- **WHEN** a spawned background task creates a child coroutine via `coroutine.wrap` or `coroutine.create` and the child coroutine calls `coroutine.yield`
- **THEN** the runtime SHALL allow the child coroutine to yield and resume within pure Lua
- **AND** the runtime SHALL NOT treat the yield as a host operation or an unrecognized yield

#### Scenario: Spawned task calls raw `coroutine.yield` at top level
- **WHEN** a spawned background task calls `coroutine.yield()` at the top level (not inside a child coroutine)
- **THEN** the runtime SHALL detect the unrecognized yield that escapes the spawned-task boundary
- **AND** it SHALL produce `E_INVALID_YIELD` for that task

#### Scenario: Sleep is called from plugin-created child coroutine
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `talkcan.runtime.sleep`
- **THEN** the runtime SHALL NOT suspend or start a timer
- **AND** the sleep call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no timer token SHALL be created

#### Scenario: Spawn is called from synchronous event callback other than startup
- **WHEN** `handle_input`, `handle_lifecycle`, `handle_readiness`, or `handle_sos` calls `talkcan.runtime.spawn`
- **THEN** the runtime SHALL NOT admit the task
- **AND** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn is called from plugin-created child coroutine
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `talkcan.runtime.spawn`
- **THEN** the runtime SHALL NOT admit the task
- **AND** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn argument is not a function
- **WHEN** a callback calls `talkcan.runtime.spawn("not_a_function")` or `talkcan.runtime.spawn(42)`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT admit a task

#### Scenario: Sleep argument is negative
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(-1.0)`
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT suspend the coroutine or start a timer

#### Scenario: Sleep argument exceeds the configured maximum delay
- **WHEN** a spawned background task calls `talkcan.runtime.sleep` with a finite duration greater than the host-configured maximum delay
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** it SHALL NOT suspend the coroutine or start a timer

#### Scenario: Sleep at zero clamps to minimum tick
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(0)`
- **THEN** the host MAY clamp the duration to its minimum timer tick
- **AND** the call SHALL proceed as a valid sleep with the clamped duration
- **AND** it SHALL return `(true, nil)` after the timer fires

#### Scenario: Sleeping coroutine is terminated on generation close
- **WHEN** a spawned background task is suspended in `talkcan.runtime.sleep` and its owning actor generation is closed or replaced
- **THEN** the host SHALL terminate the sleeping coroutine without resuming it
- **AND** the coroutine SHALL NOT receive any result, cancellation, or closed outcome
- **AND** the actor's operation tokens SHALL be invalidated and the Lua state closed

#### Scenario: Timer completion is stale after generation close
- **WHEN** a timer operation completes after its owning actor has closed
- **THEN** the host SHALL reject the completion as stale
- **AND** it SHALL NOT resume any Lua coroutine for that effect

#### Scenario: Background task loops with periodic sleep beyond a former generic task deadline
- **WHEN** a spawned background task repeatedly performs brief work and calls `talkcan.runtime.sleep`, and its cumulative wall-clock lifetime exceeds the generic deadline used by the pre-change actor policy
- **THEN** the runtime SHALL continue to resume the task across sleep boundaries because each sleep establishes a new operation-specific deadline
- **AND** the runtime SHALL NOT terminate the task or fail a sleep solely because cumulative task wall time exceeds that former generic deadline
- **AND** the task MAY remain live as long as each active slice satisfies execution limits, each sleep completes within its operation-specific deadline, and the generation remains open

#### Scenario: Sleep deadline wins before timer completion
- **WHEN** a spawned background task calls `talkcan.runtime.sleep` and its operation-specific deadline passes before timer completion
- **THEN** the host SHALL classify the operation as timed out
- **AND** it SHALL resume the sleeping coroutine exactly once
- **AND** the sleep call SHALL return `(nil, {error = "E_TIMEOUT"})`
- **AND** a later timer completion SHALL be rejected as stale without resuming Lua again
- **AND** the coroutine SHALL remain live and available for further host operations

### Requirement: Compatibility failure semantics preserve host state without persisting the program image
When the host detects that a Lua program image's declared execution requirement `luaVersion` or `apiVersion` is not equal to the host's corresponding version constant, the host SHALL refuse to create a Lua state or attempt startup. The containing channel instance SHALL project an explicit typed compatibility state. The host SHALL preserve the catalogue definition's stored configuration unchanged. The program image is host-supplied to the provider at construction time and is not part of persisted configuration; the host SHALL NOT persist, mutate, or claim to store a program-image reference in catalogue configuration. The host SHALL NOT attempt fallback to another runtime or silently adjust the program's declared execution requirements.

#### Scenario: Version incompatibility detected before state creation
- **WHEN** the program image declares a `luaVersion` or `apiVersion` not equal to the host's corresponding version constant
- **THEN** the host SHALL NOT create a Lua state
- **AND** it SHALL expose a typed compatibility-failure state in the channel instance's host projection
- **AND** it SHALL preserve the catalogue definition's stored configuration unchanged
- **AND** it SHALL NOT persist or store a program-image reference in catalogue configuration

#### Scenario: Module resolution fails at startup
- **WHEN** the program image's entry module or a required non-reserved package-local module cannot be resolved from the source map at startup
- **THEN** the host SHALL NOT publish the channel as ready
- **AND** it SHALL NOT substitute an alternative module, search for a similar name, or load a default module

### Requirement: Revised v1 reserves generic storage, audio, network, data, profile, secret, and work modules
The runtime SHALL reserve and inject `talkcan.runtime`, `talkcan.channel`, `talkcan.log`, `talkcan.transcription`, `talkcan.synthesis`, `talkcan.playback`, `talkcan.fs`, `talkcan.audio`, `talkcan.keyboard_output`, `talkcan.http`, `talkcan.json`, `talkcan.profiles`, `talkcan.secrets`, and `talkcan.work` for every ordinary package state. Resolver states SHALL receive only the restricted subset defined by their contract. Package source SHALL not define or shadow any `talkcan.*` module. Requiring a module SHALL not grant capability, resource, profile, secret, queue, or execution-context authority. API version SHALL remain exactly `talkcan-lua-v1`; no legacy preload set or alias SHALL remain.

#### Scenario: Ordinary package requires generic modules
- **WHEN** package code requires one of the revised public modules
- **THEN** require SHALL return the host injection without consulting package source
- **AND** function calls SHALL remain independently authorized

#### Scenario: Resolver requires ineligible module
- **WHEN** a resolver state requires audio, keyboard, filesystem, or work
- **THEN** its operations SHALL be absent or return the exact restricted-context denial
- **AND** no ordinary channel authority SHALL be acquired

### Requirement: Runtime exposes stable instance identity without lifecycle tokens
`talkcan.runtime.INSTANCE_ID` SHALL be an immutable valid-UTF-8 string equal to the host channel instance ID for that Lua state. It SHALL not expose mutable selection, provider credentials, runtime generation, capability-scope identity, actor pointer, or platform identity. Same-provider sibling instances SHALL observe different values; replacement generations for the same instance SHALL observe the same value.

#### Scenario: Journal attributes durable entry
- **WHEN** Lua reads `runtime.INSTANCE_ID`
- **THEN** it SHALL receive its stable channel instance ID
- **AND** it SHALL not receive a generation or platform object

### Requirement: Host effects use opaque typed operation requests
Every yielding public host call SHALL construct a bounded typed request in a state-owned registry and yield only an opaque request token. Operation labels/outcomes SHALL not encode paths, text bodies, JSON parameters, audio tokens, mount tokens, provider identifiers, or platform locations. The host dispatcher SHALL claim a request exactly once, validate its state/generation/execution/capability/resource ownership, execute the generic operation, and deliver one normalized completion. Large bounded payloads SHALL use explicit typed transport or host-managed streams rather than concatenated labels.

#### Scenario: Filesystem write yields
- **WHEN** `fs.write_text` admits a request
- **THEN** the yielded actor outcome SHALL identify only an opaque request
- **AND** the text, mount, and path SHALL remain in the bounded host request registry

#### Scenario: Opaque request is replayed
- **WHEN** the host or Lua attempts to claim a completed, foreign, stale, or unknown request token
- **THEN** the broker SHALL reject it without another effect or Lua resumption

### Requirement: Runtime injects the reserved keyboard-output module
The runtime SHALL reserve and inject `talkcan.keyboard_output` beside the existing host modules for every Lua package. The module SHALL have exactly the functions defined by `lua-keyboard-output-api`; requiring it SHALL grant no capability. Package source SHALL NOT shadow the module, and any callable invocation during entry-module or lazy-module evaluation SHALL trigger the existing effect-call-during-load failure before operation admission.

#### Scenario: Package requires keyboard output
- **WHEN** loaded callback code calls `require("talkcan.keyboard_output")`
- **THEN** the runtime SHALL return the host-injected module table without consulting package source
- **AND** the module SHALL not acquire or prepare a capability merely because it was required

#### Scenario: Source shadows keyboard-output module
- **WHEN** a package source map contains `talkcan.keyboard_output`
- **THEN** source-map validation SHALL reject the complete image before state creation

#### Scenario: Module function is called during load
- **WHEN** entry or lazy-module evaluation invokes `send_text` or `send_key`
- **THEN** the effect-call-during-load guard SHALL fail the complete module evaluation
- **AND** no text payload, host request, queue entry, capability acquisition, preparation, or physical output SHALL be created

### Requirement: Keyboard-output operations use managed execution ownership
After loading, `talkcan.keyboard_output` SHALL authorize calls only from the current host-managed input owner, SOS owner, or managed-task owner. A valid call SHALL create one typed opaque host-operation request, suspend the owner, release the serialized Lua slot, and resume at most once with the normalized terminal result while that owner and generation remain live. Selection changes SHALL NOT invalidate the owner. Ineligible contexts SHALL receive `E_INVALID_CONTEXT` before suspension or effect.

#### Scenario: Managed task yields for keyboard output
- **WHEN** a live runtime-managed task invokes a valid declared keyboard-output operation
- **THEN** the runtime SHALL suspend that task without retaining a native execution thread
- **AND** other ready actor work MAY run before the operation terminally resumes the owner

#### Scenario: Channel is deselected while task is suspended
- **WHEN** another channel becomes selected while a managed task awaits keyboard output
- **THEN** the original task and operation SHALL remain generation-authorized
- **AND** completion SHALL not enter or mutate the selected channel's actor

#### Scenario: Generation closes while owner is suspended
- **WHEN** the owning generation closes or is replaced before terminal completion
- **THEN** the runtime SHALL revoke the request and discard the suspended execution without re-entering predecessor Lua
- **AND** later host completion SHALL be stale

### Requirement: Work effect functions are protected yieldable state-local references
`Job:effect(key, function)` SHALL be the only new public function-accepting operation. The function SHALL execute immediately at most once for a new effect key under the current job's protected managed-task stack, MAY yield only through host operations authorized for the nested effect owner, and SHALL not escape, be stored in durable data, run concurrently, nest another work effect, or survive task/generation close. Its terminal normalized result SHALL be durably committed before return; a Lua throw, malformed return, raw yield, interruption, or close SHALL follow durable-work failure/indeterminate rules.

#### Scenario: Effect function yields through HTTP
- **WHEN** a job effect invokes authorized `talkcan.http.request`
- **THEN** the actor SHALL suspend and resume the same protected effect function
- **AND** commit its valid normalized result before returning to outer Lua policy

#### Scenario: Effect function is nested
- **WHEN** an active effect function calls `job:effect` again
- **THEN** the nested call SHALL fail with `E_INVALID_CONTEXT` or `E_BUSY`
- **AND** it SHALL not create a second ledger entry

### Requirement: New opaque userdata remain state and owner confined
SecretReference, Queue, Job, Effect, and JSON Null SHALL use state-local unforgeable tokens and locked metatables. SecretReference SHALL additionally bind package/profile/field/revision grant; Queue instance/package/generation/declaration; Job queue/work/lease/task; Effect job/key/task; and Null only its owning state. Stringification, equality, serialization, logging, cross-state use, stale-generation use, and unsupported method calls SHALL reveal no underlying identifier or authority and SHALL return normalized invalid/stale/denied outcomes.

#### Scenario: Job object is captured by sibling task
- **WHEN** another managed task invokes a Job method
- **THEN** ownership validation SHALL reject it before ledger mutation

#### Scenario: Opaque object is stringified
- **WHEN** Lua calls `tostring` on a new opaque value
- **THEN** it SHALL receive only a non-sensitive stable type label or protected default
- **AND** no token, profile ID, secret alias, work ID, or native address SHALL appear

