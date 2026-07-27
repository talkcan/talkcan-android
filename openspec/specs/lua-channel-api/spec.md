## Purpose

Defines Lua channel callbacks, activation, input, timers, and structured
logging exposed to host-supplied programs.
## Requirements
### Requirement: The entry module returns a validated callback table with one required non-yielding callback and optional event callbacks
A Lua program image SHALL designate exactly one entry module that, after loading, returns a plain Lua table with no metatable. The table SHALL contain exactly one required entry point: a function at key `startup`. During authorized activation the host SHALL invoke that function with exactly one detached configuration snapshot table containing `schema_version` and a `values` table of validated flat scalar values. Lua function arity is not introspected; the host SHALL always supply the snapshot and SHALL NOT provide a no-argument invocation path, legacy configuration object, or fallback callback. The generic provider SHALL validate configuration through the standard provider-construction path before producing the snapshot. Optional recognized callbacks are `handle_lifecycle`, `handle_input`, `handle_sos`, and `handle_readiness`; an absent or nil optional callback receives its documented neutral default, while a present non-function recognized callback fails construction. Unrecognized callback-table keys SHALL be ignored.

Every source-map module chunk evaluation—the entry module and every module loaded lazily via `require`—SHALL be synchronous and non-yielding. During evaluation the chunk MAY require Talkcan modules to read constants, call pure Lua functions, construct tables, define functions, and return values. It MUST NOT invoke any host-provided callable, including `talkcan.runtime.sleep`, `talkcan.runtime.spawn`, `talkcan.log` functions, or any callable on another host-injected module. A host-call attempt during module evaluation SHALL fail the complete module load or entry evaluation with a typed effect-call-during-load error, SHALL NOT cache a partial result, and SHALL NOT admit work or produce a host effect.

#### Scenario: Entry module returns a valid table with required startup callback
- **WHEN** the program image entry module executes and returns a plain table containing a function at key `startup`
- **THEN** the runtime SHALL accept the table as the program's public interface
- **AND** it SHALL NOT require `handle_lifecycle`, `handle_input`, `handle_sos`, or `handle_readiness` to be present

#### Scenario: Entry module returns a non-table value
- **WHEN** the entry module returns a number, string, boolean, nil, or function instead of a table
- **THEN** the runtime SHALL classify construction as failed with a typed entrypoint-validation error
- **AND** it SHALL NOT publish readiness

#### Scenario: Entry module returns a table with a metatable
- **WHEN** the entry module returns a table that has a metatable
- **THEN** the runtime SHALL produce a typed construction failure
- **AND** it SHALL NOT examine or interpret metatable entries or publish readiness

#### Scenario: Entry module omits required startup callback
- **WHEN** the returned table lacks the `startup` key or maps it to nil
- **THEN** the runtime SHALL produce a typed missing-callback error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Required startup callback has wrong type
- **WHEN** the returned table maps `startup` to a non-function value
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT register a partial callback table or substitute a default

#### Scenario: Optional callback has wrong type
- **WHEN** a recognized optional callback key maps to a non-nil non-function value
- **THEN** the runtime SHALL produce a typed invalid-callback-type error at construction
- **AND** it SHALL NOT treat the value as absent or apply the neutral default

#### Scenario: Optional callback is absent or nil
- **WHEN** a recognized optional callback key is absent or maps to nil
- **THEN** the runtime SHALL apply that callback's documented neutral default
- **AND** it SHALL NOT raise an error for the missing callback

#### Scenario: Host invokes startup with validated configuration
- **WHEN** authorized activation invokes the required `startup` function
- **THEN** the host SHALL pass exactly one detached configuration snapshot argument
- **AND** it SHALL NOT retry the invocation without arguments or expose a legacy configuration object when the callback ignores or mishandles that argument

#### Scenario: Entry module attempts host call during top-level evaluation
- **WHEN** the entry module invokes any host-injected callable before returning the callback table
- **THEN** the runtime SHALL fail construction with the typed effect-call-during-load outcome
- **AND** no background task, timer, log, capability operation, or partial module result SHALL be admitted or retained

### Requirement: Event callbacks have explicit yield contexts and exact terminal results
`startup`, `handle_lifecycle`, and `handle_readiness` SHALL execute synchronously and SHALL NOT suspend across the host boundary. `handle_input` and `handle_sos` SHALL execute in host-managed yield-capable coroutines. The host SHALL reject a raw `coroutine.yield` escaping any callback stack as a typed callback-contract failure. Outside source/module evaluation, a context-restricted host operation called from an ineligible callback SHALL return its normal `(nil, {error = "E_INVALID_CONTEXT"})` pair before suspension or effect; that denied call SHALL NOT by itself fail the callback if Lua handles or ignores it. `startup` MAY call `talkcan.runtime.spawn` to admit managed background tasks. Lifecycle, SOS, and readiness callbacks SHALL receive `E_INVALID_CONTEXT` from `spawn` and `defer` without task admission. Input SHALL receive `E_INVALID_CONTEXT` from `spawn` but MAY call `talkcan.runtime.defer` under its terminal-bound contract.

`handle_input` MAY invoke authorized yielding operations defined by the public Lua capability APIs available to input execution owners. `handle_sos` MAY invoke authorized yielding operations explicitly permitted for SOS execution owners, including declared `keyboard.output`. Each admitted operation MAY suspend its callback, release the serialized Lua execution slot, and resume it exactly once with a terminal result while the owner and generation remain current. `talkcan.runtime.sleep`, `talkcan.runtime.spawn`, and raw escaping `coroutine.yield` are not authorized input or SOS suspension paths. Sleep and spawn SHALL return `E_INVALID_CONTEXT`; raw escaping yield SHALL fail the callback. `runtime.defer` is synchronous input-only task reservation and SHALL return `E_INVALID_CONTEXT` from SOS.

For `startup` and `handle_lifecycle`, a normal return without an `error` field SHALL be success/no-op according to callback role. `handle_sos` SHALL return exactly `{ok = true}` or `{error = {code = <non-empty string>, detail = <non-empty string>}}`. An application failure for other general callbacks SHALL be exactly `{error = {code = <non-empty string>, detail = <non-empty string>}}`; any malformed table containing `error` SHALL be a typed callback-contract failure. `handle_input` and `handle_readiness` SHALL use their separately defined exact result shapes.

#### Scenario: Startup admits a background task
- **WHEN** `startup` calls `talkcan.runtime.spawn(function() ... end)` and returns successfully
- **THEN** the runtime SHALL synchronously admit and queue the task
- **AND** the task SHALL NOT begin until activation completes
- **AND** startup itself SHALL NOT yield

#### Scenario: Input callback calls spawn
- **WHEN** `handle_input` calls `talkcan.runtime.spawn`
- **THEN** it SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` without admitting or executing a task
- **AND** the callback MAY handle that result and continue

#### Scenario: Input callback uses authorized yielding operation
- **WHEN** `handle_input` invokes an authorized semantic-audio, audio-file, filesystem, or keyboard-output operation
- **THEN** the host SHALL suspend that callback and release the execution slot
- **AND** it SHALL resume the callback exactly once while its execution owner remains current
- **AND** it SHALL evaluate the callback result only after terminal completion

#### Scenario: SOS callback uses authorized yielding operation
- **WHEN** `handle_sos` invokes an authorized keyboard-output operation
- **THEN** the host SHALL suspend that callback and release the execution slot
- **AND** it SHALL resume the callback at most once while its SOS execution owner remains current
- **AND** raw yield, sleep, spawn, and defer SHALL remain unavailable

#### Scenario: Input callback defers post-input work
- **WHEN** `handle_input` successfully calls `runtime.defer(function() ... end)`
- **THEN** the runtime SHALL reserve but not execute the task until the callback returns exact success

#### Scenario: Input callback returns success
- **WHEN** `handle_input` returns exactly `{ok = true}`
- **THEN** the host SHALL record input SUCCESS and SHALL NOT invoke another callback for that input
- **AND** it SHALL release transient input resources and release committed deferred tasks to the managed scheduler

#### Scenario: Input callback returns application failure
- **WHEN** `handle_input` returns exactly `{error = {code = "E_CAPTURE_FAILURE", detail = "processing failed"}}`
- **THEN** the host SHALL record input FAILED without automatic replay
- **AND** it SHALL discard every task deferred by that invocation

#### Scenario: Input callback returns malformed result
- **WHEN** `handle_input` returns a non-table, unknown key, both `ok` and `error`, an `ok` value other than `true`, or malformed error object
- **THEN** the host SHALL reject the complete result as a typed callback-contract failure
- **AND** it SHALL record input FAILED and discard deferred tasks

#### Scenario: Input callback throws or yields raw coroutine
- **WHEN** `handle_input` throws or an unrecognized raw `coroutine.yield` escapes
- **THEN** the host SHALL record input FAILED with a locally contained typed callback failure
- **AND** it SHALL discard deferred tasks without crashing the actor or application

### Requirement: Activation sequence is serialized and fail closed
After callback-table validation and authorization, the host SHALL invoke `startup(configuration)` exactly once as a synchronous callback. If it succeeds, the host SHALL invoke present `handle_lifecycle({event = "ready"})` synchronously before publishing Ready. A task admitted by startup `spawn` SHALL remain queued until both activation callbacks have succeeded and Ready publication occurs. A thrown Lua error, application error result, malformed error result, or raw coroutine yield escaping either activation callback SHALL fail activation, discard every startup-admitted task, and prevent Ready. A context-denied host call that returns `E_INVALID_CONTEXT` SHALL affect activation only through the callback's eventual returned result or thrown error.

#### Scenario: Successful activation publishes Ready
- **WHEN** `startup(configuration)` and present lifecycle-ready callback return successfully
- **THEN** the host SHALL publish Ready after the lifecycle step
- **AND** only then SHALL startup-admitted tasks become runnable

#### Scenario: Startup failure discards admitted tasks
- **WHEN** startup throws, returns a valid application failure, returns malformed failure data, or lets a raw coroutine yield escape
- **THEN** the host SHALL fail activation without publishing Ready
- **AND** it SHALL discard and cancel every task admitted during startup before any can execute

#### Scenario: Lifecycle-ready failure discards admitted tasks
- **WHEN** lifecycle-ready throws, returns a valid application failure, returns malformed failure data, or lets a raw coroutine yield escape
- **THEN** the host SHALL fail activation without publishing Ready
- **AND** it SHALL discard and cancel every task admitted during startup before any can execute

### Requirement: `talkcan.channel` provides event constants for callback argument matching
The `talkcan.channel` module SHALL be a host-injected, reserved module that plugins can `require` to access channel event type constants. The plugin SHALL NOT override or shadow the injected `talkcan.channel` module with source-map content. In v1 the module SHALL define only three string constants: `LIFECYCLE_READY = "ready"`, `CAPTURE_COMPLETE = "capture"`, and `SOS_TRIGGERED = "sos"`. There SHALL be no callable functions on the module in v1; the plugin's callback table is the sole interface for channel lifecycle, input, SOS, and readiness projection. Callbacks receive event tables whose `event` string field corresponds to one of these constants.

#### Scenario: Plugin reads event constants
- **WHEN** a callback reads `talkcan.channel.LIFECYCLE_READY`, `talkcan.channel.CAPTURE_COMPLETE`, or `talkcan.channel.SOS_TRIGGERED`
- **THEN** it SHALL receive the exact strings `"ready"`, `"capture"`, and `"sos"` respectively

#### Scenario: Lifecycle callback is invoked after startup before Ready publication
- **WHEN** the host has completed `startup` successfully and is about to publish the channel as Ready, and the plugin has supplied `handle_lifecycle`
- **THEN** the host SHALL invoke the callback with a table containing `event` set to `"ready"` matching `talkcan.channel.LIFECYCLE_READY`
- **AND** the callback SHALL receive no platform objects, actor identities, or audio data
- **AND** the host SHALL publish Ready immediately after the callback returns successfully

#### Scenario: Lifecycle callback is absent
- **WHEN** the callback table lacks `handle_lifecycle`
- **THEN** lifecycle events SHALL produce no effect
- **AND** the host SHALL proceed directly from `startup` success to Ready publication

#### Scenario: Readiness callback projects readiness status and preparation
- **WHEN** the host refreshes readiness and `handle_readiness` is present
- **THEN** it SHALL invoke the callback synchronously with one context table whose `capabilities` map contains every declared public capability ID exactly once with value `available`, `recoverable`, or `unavailable`, whose resource map follows declared resource contracts, and whose configuration-reference map contains every required dynamic scalar reference exactly once with value `available` or `unavailable`
- **AND** the callback SHALL return a plain exact-key table containing required boolean `ready`, optional bounded valid-UTF-8 string `status`, and optional bounded duplicate-free array `prepare`
- **AND** every `prepare` entry SHALL name a declared host-preparable public capability, and nonempty `prepare` SHALL be valid only when `ready = false`
- **AND** a missing callback SHALL use the neutral not-ready default with no preparation request
- **AND** a thrown error, raw yield, non-table, error table, missing or non-boolean `ready`, unknown result key, metatable, invalid status, invalid prepare array, unknown/duplicate/undeclared/non-preparable capability, or `ready = true` with nonempty prepare SHALL cache not-ready with no preparation and record one local typed failure

#### Scenario: Readiness callback updates host-visible availability
- **WHEN** the host obtains a valid or invalid readiness projection
- **THEN** `ready = true` SHALL publish the runtime snapshot as available
- **AND** `ready = false` with a nonempty valid `prepare` array SHALL publish it as recoverable
- **AND** `ready = false` without a preparation path, a missing callback, or a malformed result SHALL publish it as unavailable
- **AND** the phone and car catalogue projections SHALL derive their readiness status from that current runtime snapshot rather than activation state

#### Scenario: Readiness callback filters by selected dependency subset
- **WHEN** `handle_readiness` supports configurations or modes depending on different capabilities
- **THEN** it SHALL evaluate only the selected dependency subset
- **AND** it MAY request preparation only for the selected subset's declared recoverable dependencies

#### Scenario: Cached readiness accepts input
- **WHEN** cached readiness is `ready = true` and `handle_input` is valid
- **THEN** the host SHALL accept input without a new Lua call at initial acceptance
- **AND** capture SHALL proceed

#### Scenario: Cached readiness requests preparation
- **WHEN** cached readiness is false with a nonempty valid `prepare` array
- **THEN** the host SHALL run bounded generic preparation before accepting input
- **AND** it SHALL refresh readiness after successful preparation
- **AND** capture SHALL proceed only if refreshed readiness is true

#### Scenario: Input is rejected because readiness is false
- **WHEN** cached readiness is false with no successful preparation path or `handle_input` is absent
- **THEN** the host SHALL refuse input with a typed result
- **AND** capture SHALL NOT start

#### Scenario: Input capture lifecycle delivers opaque audio userdata
- **WHEN** capture completes successfully after the snapshot entered RECORDING
- **THEN** the snapshot SHALL enter PROCESSING and the host SHALL invoke `handle_input` with event `capture`, session identity, bounded metadata, and opaque unforgeable recording userdata
- **AND** the callback SHALL execute in its yield-capable context

#### Scenario: Input capture is cancelled by host
- **WHEN** capture is cancelled before release
- **THEN** the snapshot SHALL enter IDLE
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: Input capture fails before release
- **WHEN** the host capture subsystem fails before release
- **THEN** the snapshot SHALL enter FAILED
- **AND** `handle_input` SHALL NOT be invoked

#### Scenario: SOS callback completes through managed coroutine
- **WHEN** SOS occurs and `handle_sos` is present
- **THEN** the host SHALL invoke it with event `sos` matching `talkcan.channel.SOS_TRIGGERED` under one bounded SOS execution owner
- **AND** exact success SHALL complete the SOS invocation once
- **AND** application failure, malformed result, throw, invalid yield, cancellation, timeout, or revocation SHALL be locally contained and diagnosed without crashing the actor or unrelated generation

#### Scenario: SOS callback is absent
- **WHEN** the callback table lacks `handle_sos`
- **THEN** SOS events SHALL be unhandled
- **AND** no Lua callback SHALL be invoked

#### Scenario: Proactive-only plugin omits optional callbacks
- **WHEN** the callback table contains only required `startup`
- **THEN** the host SHALL keep successfully admitted managed background tasks live
- **AND** the channel SHALL not be available for PTT input selection and readiness SHALL default to not ready

### Requirement: `talkcan.runtime` provides runtime identity and cooperative timer operations with validated arguments
The `talkcan.runtime` module SHALL be a host-injected, reserved module. It SHALL expose exact runtime and API version constants: `LUA_VERSION` as `"Lua 5.4"`, `LUA_RELEASE` as `"5.4.8"`, and `API_VERSION` as `"talkcan-lua-v1"`. There SHALL be no `version()` function, no integer runtime version, and no integer API version. The module SHALL provide cooperative timer operations: `sleep(seconds)` to suspend the current coroutine for at least the specified duration (usable only from runtime-managed spawned background tasks, not from event callbacks, entry evaluation, or plugin-created child coroutines), and `spawn(function)` to synchronously admit a background task (usable only from `startup` and runtime-managed spawned tasks, not from event callbacks, entry evaluation, or plugin-created child coroutines).

Both `sleep` and `spawn` SHALL return `(value, nil)` on success or `(nil, error_table)` on failure; the plugin SHALL NOT receive an operation token, coroutine reference, userdata, or handle from either call. A `sleep` call SHALL resume after the requested delay regardless of whether the channel is selected or unselected. A `spawn` call SHALL synchronously admit the task and return immediately without blocking the caller. `spawn` SHALL accept only a function argument; a non-function argument SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`. `sleep` SHALL accept only a finite number >= 0; negative, NaN, infinity, or above-maximum values SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`. Exhaustion of per-generation limits SHALL return `(nil, {error = "E_BUSY"})`. Invalid context SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`. The runtime SHALL NOT expose `os.execute`, `os.tmpname`, `io.*`, `file.*`, `debug.*`, `loadfile`, or `string.dump`.

#### Scenario: Program queries runtime version constants
- **WHEN** a callback reads `talkcan.runtime.LUA_VERSION`
- **THEN** it SHALL receive the exact string `"Lua 5.4"`
- **AND** reading `talkcan.runtime.LUA_RELEASE` SHALL return the exact string `"5.4.8"`
- **AND** reading `talkcan.runtime.API_VERSION` SHALL return the exact string `"talkcan-lua-v1"`

#### Scenario: Startup synchronously admits a background spawn
- **WHEN** the `startup` callback calls `talkcan.runtime.spawn(function() ... end)`
- **THEN** the runtime SHALL synchronously admit the background task
- **AND** the spawn call SHALL return `(true, nil)`
- **AND** the background coroutine SHALL be queued and SHALL begin executing only after the activation sequence completes successfully

#### Scenario: Spawn fails because the task-admission limit is reached
- **WHEN** a callback calls `talkcan.runtime.spawn(function() ... end)` and the per-generation task-admission limit is reached
- **THEN** the spawn call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the function SHALL NOT be executed or queued

#### Scenario: Spawn fails because the argument is not a function
- **WHEN** a callback calls `talkcan.runtime.spawn(42)` or `talkcan.runtime.spawn("bad")`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** no task SHALL be admitted

#### Scenario: Spawn called from invalid context
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `talkcan.runtime.spawn`
- **THEN** the spawn call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no task SHALL be admitted

#### Scenario: Spawned task calls sleep and resumes
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(5.0)`
- **THEN** the host SHALL suspend the coroutine and set a timer for at least 5 seconds
- **AND** after the timer fires, the host SHALL resume the coroutine
- **AND** the sleep call SHALL return `(true, nil)`

#### Scenario: Sleep fails because the timer limit is reached
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(5.0)` and the per-generation timer slot limit is reached
- **THEN** the sleep call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** the coroutine SHALL NOT be suspended

#### Scenario: Sleep fails because the argument is invalid
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(-1.0)` or `talkcan.runtime.sleep(1e6)` (above maximum)
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_ARGUMENT"})`
- **AND** the coroutine SHALL NOT be suspended and no timer SHALL be started

#### Scenario: Sleep fails because context is invalid
- **WHEN** a spawned background task creates a child coroutine via `coroutine.create` and that child coroutine calls `talkcan.runtime.sleep`
- **THEN** the sleep call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no timer SHALL be started and no coroutine suspension SHALL occur

#### Scenario: Sleep at zero clamps to minimum tick
- **WHEN** a spawned background task calls `talkcan.runtime.sleep(0)`
- **THEN** the host MAY clamp the duration to its minimum timer tick
- **AND** the call SHALL proceed as a valid sleep and SHALL return `(true, nil)` after the timer fires

#### Scenario: Sleep fires while the channel is unselected
- **WHEN** a spawned background task sets a timer and the channel is not the active selection when the timer fires
- **THEN** the runtime SHALL resume the background task's coroutine regardless of selection state
- **AND** it SHALL NOT require the channel to be selected for background work to progress

#### Scenario: Program accesses restricted standard library
- **WHEN** a callback attempts to call `os.execute`, `io.open`, `debug.getregistry`, `loadfile`, or `string.dump`
- **THEN** the runtime SHALL raise an error that the function is disabled or nil
- **AND** the callback SHALL NOT execute a restricted operation

### Requirement: `talkcan.log` provides bounded structured logging with typed validation
The `talkcan.log` module SHALL be a host-injected, reserved module. It SHALL expose four named log functions: `debug`, `info`, `warn`, and `error`. Each function SHALL accept a single structured payload table (not a level-plus-payload pair). Log calls SHALL be non-blocking, non-yielding, and SHALL be rate-limited such that an excessive number of log entries from one actor does not exhaust host memory. The log entry SHALL include the channel instance identifier, runtime generation, a timestamp, the level, and the provided payload. Log payloads SHALL be normalized Lua tables without executable code, file handles, userdata, or coroutine references. Log functions are usable from the `startup` callback and all subsequent event callbacks and spawned background tasks.

Each log function SHALL return `(true, nil)` when the entry is recorded or silently rate-dropped within the bounded actor window, and `(nil, {error = "E_INVALID_VALUE"})` when the whole payload is rejected as invalid. The host SHALL NOT recursively log an invalid payload, SHALL NOT strip or partially write fields, and SHALL NOT suspend, fail, or crash the calling coroutine when a payload is rejected.

Every record actually accepted into the actor's bounded log buffer SHALL be offered exactly once to a bounded host observability sink. The sink SHALL map the Lua level to the corresponding host log level, use a host-owned plugin tag, and serialize only the host-attributed instance ID, runtime generation, timestamp, and normalized payload. It SHALL write no throwable and SHALL NOT accept a plugin-supplied tag, timestamp, instance identity, generation, or log level. A record silently dropped by the actor rate limit, rejected by value validation, or received after its generation has closed SHALL NOT enter host observability. Host-sink saturation or persistence failure SHALL remain bounded, SHALL NOT block or re-enter Lua, and SHALL NOT change the result already returned to the plugin.

#### Scenario: Program writes a structured log entry
- **WHEN** a callback executes `talkcan.log.info({message = "timer fired", duration_ms = 1520})`
- **THEN** the runtime SHALL record a structured log entry associated with the calling channel instance
- **AND** the entry SHALL include the instance identifier, runtime generation, timestamp, level `info`, and the supplied payload fields
- **AND** the function SHALL return `(true, nil)`
- **AND** the accepted record SHALL be offered exactly once to the bounded host observability sink

#### Scenario: Log payload contains disallowed types
- **WHEN** a callback calls `talkcan.log.warn({handle = some_userdata})`
- **THEN** the runtime SHALL reject the entire log call with a typed validation result
- **AND** the function SHALL return `(nil, {error = "E_INVALID_VALUE"})`
- **AND** it SHALL NOT write a partial or incomplete actor or host log entry
- **AND** it SHALL NOT recursively log the invalid payload
- **AND** it SHALL NOT suspend, fail, or crash the calling coroutine

#### Scenario: Log rate limit is exceeded
- **WHEN** a callback issues log entries faster than the bounded actor rate limit allows
- **THEN** the runtime SHALL silently drop subsequent log entries within that actor's current window
- **AND** the function SHALL return `(true, nil)` for dropped entries
- **AND** it SHALL NOT forward dropped entries to host observability
- **AND** it SHALL NOT block the caller or allocate growing buffers for dropped entries

#### Scenario: Accepted record is mapped to host observability
- **WHEN** an accepted Lua log record reaches the host sink while its runtime generation remains live
- **THEN** the sink SHALL preserve its semantic level and host timestamp and SHALL encode its normalized payload under a host-owned plugin tag
- **AND** the persistent and reactive log surfaces SHALL receive at most one corresponding entry

#### Scenario: Generation closes before a pending record is published
- **WHEN** a runtime generation closes or is replaced before its pending accepted record can enter the host sink
- **THEN** the sink SHALL suppress the stale record or retain its original predecessor attribution according to the accepted-publication boundary
- **AND** SHALL never attribute that record to the successor generation

#### Scenario: Host observability sink is saturated
- **WHEN** the bounded host sink cannot accept another plugin record immediately
- **THEN** the host SHALL drop or coalesce the projection under bounded policy and record only bounded host-owned loss diagnostics
- **AND** SHALL NOT block Lua, grow an overflow queue, retry without bound, or report a second result to the plugin

### Requirement: Callback runtime injects the semantic audio modules defined by Lua Audio API
In addition to the existing `talkcan.runtime`, `talkcan.channel`, and `talkcan.log` modules, the callback runtime SHALL reserve and inject `talkcan.transcription`, `talkcan.synthesis`, and `talkcan.playback` for every package. Requiring these modules SHALL NOT grant a capability. Their functions, normalized arguments and results, stable errors, context eligibility, deadlines, opaque-userdata ownership, consume/dispose rules, cancellation, and revocation semantics SHALL be exactly those defined by `lua-audio-api`; this provider contract SHALL NOT define an alternative audio surface. The provider adapter SHALL map each eligible invocation through the current execution owner, runtime generation context, and revocable semantic capability scope without exposing host implementation objects.

During entry or lazy-module evaluation, any call to one of these host-injected functions SHALL be handled by the effect-call-during-load guard and fail the complete module evaluation without returning a normal Lua error pair. After loading, an ineligible callback or unmanaged coroutine SHALL receive `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or host effect. An eligible `handle_input` callback or runtime-managed spawned task MAY yield through these modules under the execution-owner rules.

#### Scenario: Input callback uses audio module through provider adapter
- **WHEN** an eligible `handle_input` callback invokes a declared semantic audio operation
- **THEN** the provider adapter SHALL delegate through the current generation and input-invocation owner
- **AND** the Lua-visible result and resource lifecycle SHALL follow `lua-audio-api` exactly

#### Scenario: Spawned task uses audio module through provider adapter
- **WHEN** an eligible runtime-managed spawned task invokes synthesis or playback
- **THEN** the provider adapter SHALL delegate through that task's execution-owner identity and current generation
- **AND** task termination, timeout, cancellation, and generation revocation SHALL use the `lua-audio-api` lifecycle without a callback-invocation fallback

#### Scenario: Module evaluation attempts audio effect
- **WHEN** an entry or lazy-loaded module calls a semantic audio function during top-level evaluation
- **THEN** the effect-call-during-load guard SHALL fail and discard the complete module evaluation
- **AND** no capability acquisition, operation, audio resource, queue entry, or partial module cache value SHALL be created

#### Scenario: Live input cancellation differs from generation close
- **WHEN** a live generation cancels an input invocation suspended in an audio operation
- **THEN** the provider SHALL permit the `lua-audio-api` cancellation path to resume that callback exactly once with `E_CANCELLED`
- **AND** when the generation instead retires or closes, the provider SHALL discard suspended executions without re-entering Lua and revoke all generation-owned operations, audio tokens, leases, and queued playback

### Requirement: Capture events include authoritative portable wall-clock metadata
Every `handle_input` capture event SHALL include exact `timestamp` with integer `unix_ms` and exact `local_time` containing integers `year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`, and `utc_offset_minutes`. The host SHALL capture the instant and local offset at input release before invoking Lua and SHALL validate all fields against bounded calendar ranges. The event SHALL expose no Android date/time object, timezone object, locale, formatter, or mutable clock reference.

#### Scenario: Input receives timestamp
- **WHEN** the host releases a completed capture for Lua processing
- **THEN** the event SHALL contain one authoritative Unix timestamp and matching local calendar/offset fields
- **AND** the package SHALL be able to derive deterministic names without Lua `os`

### Requirement: Readiness includes declared resource status
When a provider declares resources, `handle_readiness` context SHALL contain exact `resources.mounts` mapping every declared mount ID once to `available`, `read-only`, `needs-reauthorization`, or `unavailable`. Undeclared mounts, platform grants, paths, URIs, URLs, document IDs, and diagnostics SHALL be absent. The callback remains synchronous and non-yielding, and cached resource status SHALL not authorize later effects.

#### Scenario: Package checks required output mount
- **WHEN** readiness runs for a package declaring mount `output`
- **THEN** `context.resources.mounts.output` SHALL contain one portable status
- **AND** the callback SHALL perform no storage I/O to obtain it

### Requirement: `runtime.defer` admits terminal-bound managed tasks
`talkcan.runtime.defer` SHALL accept exactly one function and be usable only from the current host-managed `handle_input` coroutine. It SHALL synchronously reserve bounded managed-task capacity and return `(true, nil)` or a normalized error without running the function. Exact input success SHALL release the function as a new managed task with its own execution owner; input failure, malformed return, throw, cancellation, generation close, or unsuccessful terminal delivery SHALL discard it. Deferred code SHALL not inherit input audio ownership and SHALL follow ordinary task cancellation, yielding, quota, logging, and close rules after start.

#### Scenario: Successful input releases deferred task
- **WHEN** an input invocation admitted a deferred function and returns `{ok=true}`
- **THEN** the task SHALL become runnable only after terminal success is committed
- **AND** it SHALL execute under a new task owner

#### Scenario: Cancelled input discards deferred task
- **WHEN** the input invocation is cancelled before successful terminal commit
- **THEN** every function deferred by that invocation SHALL be discarded without execution

#### Scenario: Defer capacity is exhausted
- **WHEN** an input calls `defer` after reaching the generation's reservation bound
- **THEN** the call SHALL return `(nil, {error = "E_BUSY"})`
- **AND** no task SHALL be retained

### Requirement: Input callbacks may durably submit work without retaining input authority
A yield-capable `handle_input` callback MAY call `Queue:submit` for a manifest-declared queue when the package declared `work.queue`. Submission SHALL remain owned by the input execution until its durable admission result returns. Exact `{ok=true}` MAY be returned only after successful durable commit. The resulting queue item SHALL not retain or inherit the callback, Recording, audio route, input operation, opaque audio userdata, secret/profile reference, or input execution owner. Input failure, cancellation, malformed terminal result, timeout, or generation closure before successful commit SHALL create no accepted item.

#### Scenario: Transcript is durably admitted
- **WHEN** input transcribes its Recording and queue submission commits
- **THEN** the callback MAY return `{ok=true}` while a managed worker processes the item later
- **AND** callback/audio ownership SHALL end under the existing terminal rules

#### Scenario: Submission is cancelled before commit
- **WHEN** input cancellation wins before durable admission
- **THEN** the callback SHALL receive cancellation while live or be discarded on close
- **AND** no worker SHALL observe a partial item

### Requirement: Startup may acquire declared queue handles before admitting workers
Synchronous `startup(configuration)` MAY call `talkcan.work.open` for a declared queue because opening performs bounded state-local binding without persistent mutation or suspension. It MAY retain the Queue in a function passed to `talkcan.runtime.spawn`; the task SHALL begin only after successful activation. Startup SHALL NOT call `Queue:receive`, submit work, claim Jobs, resolve secrets, issue HTTP, or execute work effects.

#### Scenario: Startup creates worker closure
- **WHEN** startup opens `turns` and spawns a function that later calls `receive`
- **THEN** activation SHALL remain synchronous and side-effect free apart from bounded task admission
- **AND** receive SHALL begin only after Ready publication

### Requirement: Dynamic resolver callbacks are not channel lifecycle callbacks
Package choice-resolver modules and their `resolve` function SHALL use the separate resolver contract and SHALL not be added to or invoked through the channel entry module callback table. Resolver execution SHALL not call `startup`, `handle_readiness`, `handle_lifecycle`, `handle_input`, or `handle_sos`, and its return SHALL not update runtime snapshots directly. This separation SHALL preserve the existing exact channel callback table for packages that declare no resolver.

#### Scenario: Package has both channel and resolver modules
- **WHEN** the editor invokes the resolver module
- **THEN** the channel entry callbacks SHALL not execute
- **AND** later channel construction SHALL load its entry module independently

### Requirement: Job effect functions are task-local protected scopes rather than callbacks
A function passed to `Job:effect` SHALL execute only inside the calling managed task after the durable effect-start boundary. It SHALL not become part of the channel callback table, actor mailbox, lifecycle event set, or task registry; it SHALL return to the caller only after durable result commit. Errors and raw yields SHALL be contained to the effect/job according to durable-work rules unless independent actor integrity limits make the generation fatal.

#### Scenario: Effect function throws
- **WHEN** a job effect function raises a Lua error after its start marker
- **THEN** the job SHALL fail or become indeterminate under effect evidence
- **AND** the host SHALL not invoke a channel callback as fallback

