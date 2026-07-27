# lua-durable-work-api Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Packages declare a bounded set of named durable queues
A package manifest SHALL contain an exact bounded `workQueues` array of unique canonical package-local queue IDs. Durable work authority SHALL require the exact public capability identifier `work.queue`; declaring the capability without a queue SHALL grant no usable queue, and declaring queues without the capability SHALL fail static validation. Queue identity SHALL be scoped by declaring repository identity and channel instance. Runtime-created names, cross-instance queues, cross-package queues, anonymous queues, and unbounded queue creation SHALL be prohibited.

#### Scenario: Package declares turns queue
- **WHEN** a valid package declares `work.queue` and queue ID `turns`
- **THEN** the provider SHALL materialize one durable FIFO namespace per channel instance for that ID
- **AND** no queue record SHALL be created until Lua submits work

#### Scenario: Runtime opens undeclared queue
- **WHEN** Lua calls `work.open("other")`
- **THEN** the call SHALL return `E_CAPABILITY_UNDECLARED` or `E_NOT_FOUND`
- **AND** no dynamic queue SHALL be created

### Requirement: `talkcan.work` exposes idiomatic queue and job objects
The host SHALL reserve and inject `talkcan.work`. It SHALL expose exactly `open(queue_id)` returning an opaque state-local Queue object. Queue SHALL expose `submit(payload)` and `receive()`. A claimed opaque Job SHALL expose `payload()`, `effect(key, function)`, `complete(optional_result)`, and `fail(error)`. Methods SHALL use `(value, nil)` or `(nil, error_table)`, colon-call semantics, locked userdata metatables, and no host operation IDs, ledger paths, database handles, locks, leases, epochs, or platform objects. Requiring the module SHALL not grant undeclared work authority.

#### Scenario: Startup opens queue
- **WHEN** live startup calls `work.open` for a declared queue
- **THEN** it SHALL receive a generation-owned Queue without yielding or creating durable work
- **AND** the Queue MAY be retained by managed tasks in that generation

#### Scenario: Foreign object is used
- **WHEN** a queue or job object is used from another state, instance, generation, or execution owner
- **THEN** the method SHALL return `E_STALE`, `E_CLOSED`, or `E_DENIED`
- **AND** it SHALL not mutate durable state

### Requirement: Queue submission is durably committed before success
`Queue:submit(payload)` SHALL accept one bounded normalized value containing no function, thread, metatable, JSON-null sentinel, secret-reference userdata, profile userdata, audio, mount, queue, job, effect, or other userdata. It SHALL validate the complete payload and quotas, allocate stable work identity and FIFO sequence, and durably commit them atomically before returning a bounded submission result. Submission SHALL be callable from a live input owner or managed-task owner. A failed, cancelled, timed-out, stale, or over-capacity submission SHALL create no partially visible item.

#### Scenario: Input submits transcribed turn
- **WHEN** a live input callback submits a bounded user-text payload
- **THEN** success SHALL mean the payload and FIFO position survive process death
- **AND** the callback MAY return without waiting for queue processing

#### Scenario: Payload contains secret reference
- **WHEN** Lua submits a profile secret-reference userdata or unsupported value
- **THEN** the whole call SHALL fail with `E_INVALID_VALUE`
- **AND** no partial work record SHALL be persisted

### Requirement: Each queue serializes claims in FIFO order
`Queue:receive()` SHALL be a yielding operation callable only by a live runtime-managed task. It SHALL wait without polling or retaining an operating-system thread until the earliest safe nonterminal item is claimable, queue/generation closure occurs, or the operation terminates. At most one Job SHALL be active per queue instance. Later submissions SHALL remain durable while an earlier item is active, failed, cancelled, or reconciled. Different queue IDs and channel instances MAY progress independently under bounded process quotas.

#### Scenario: Two turns are submitted
- **WHEN** two items are durably admitted to one queue
- **THEN** `receive` SHALL claim the earlier sequence first
- **AND** it SHALL not claim the second concurrently

#### Scenario: Two channel instances have work
- **WHEN** separate instances have claimable items
- **THEN** each instance MAY progress through its own queue
- **AND** one instance's failure SHALL not reorder or discard the other

### Requirement: Job payload is detached and owner local
`Job:payload()` SHALL return a detached Lua copy of the committed payload to the current job owner without yielding. Lua mutation SHALL not alter the durable record or a future recovery copy. The host SHALL not interpret payload keys or values as provider messages, protocols, models, tools, retries, or application state. Repeated payload calls MAY return detached equal values and SHALL remain bounded.

#### Scenario: Lua mutates payload
- **WHEN** a worker edits the table returned by `payload()`
- **THEN** the durable payload SHALL remain unchanged
- **AND** restart recovery SHALL use the committed value

### Requirement: Job effects provide durable memoization around one external effect
`Job:effect(key, function)` SHALL accept a bounded canonical key unique within that job and one Lua function. It SHALL be callable only by the current job-owning managed task. For a new key, the host SHALL durably reserve and mark the effect started before invoking the function under a protected yield-capable effect owner. The function MAY perform authorized host effects and SHALL return a bounded normalized `(value, nil)` or `(nil, error_table)` result containing no unsupported userdata. The host SHALL durably commit that terminal result before `Job:effect` returns it. For an already committed same-key effect, the method SHALL return the stored result without invoking the function. Nested effects, concurrent effects for one job, duplicate keys with incompatible committed data, raw yields, and function escape SHALL fail closed.

#### Scenario: New HTTP effect completes
- **WHEN** a worker calls `job:effect("completion:1", function)` and the function returns a valid normalized completion
- **THEN** the host SHALL commit the result before returning it
- **AND** later use of the same key SHALL return that result without another HTTP call

#### Scenario: Committed keyboard effect is revisited after restart
- **WHEN** recovery reruns linear Lua policy and reaches a committed tool-effect key
- **THEN** `job:effect` SHALL return the committed normalized tool result
- **AND** it SHALL not invoke keyboard output again

#### Scenario: Effect function returns unsupported value
- **WHEN** the function returns audio, a secret/profile handle, JSON null, function, thread, cyclic table, or over-bound value
- **THEN** commit SHALL fail safely and the job SHALL reach a typed failed or indeterminate outcome according to whether an external effect may have begun

### Requirement: Ambiguous effects are never automatically replayed
Once an effect is durably marked started, process death, generation loss, task failure, timeout, cancellation, or shutdown before terminal result commit SHALL make that effect and job `indeterminate`. The host SHALL not requeue, reinvoke, or permit another function under that key. A false-positive indeterminate outcome caused by interruption after the marker but before the external call is an accepted safety trade-off. Lua or host policy SHALL not claim exactly-once delivery across an external boundary.

#### Scenario: Process dies during completion request
- **WHEN** restart finds a started effect without committed result
- **THEN** reconciliation SHALL terminalize the job as indeterminate
- **AND** the request function SHALL never be called by automatic recovery

#### Scenario: Process dies before effect starts
- **WHEN** restart finds a claimed item with no started effect
- **THEN** the item SHALL become safely claimable again in its original FIFO position

### Requirement: Linear job policy resumes from committed effects
After process restart with the same package revision, configuration revision, and work epoch, a safely reclaimable job SHALL be delivered to a fresh generation from the beginning of package policy. Pure Lua computation MAY repeat. Each previously committed effect key SHALL return its stored result, enabling policy to reconstruct bounded continuation state without persisted Lua stacks or coroutines. Volatile package state and completed prior jobs SHALL not be injected automatically.

#### Scenario: Tool loop resumes after committed model response
- **WHEN** a response effect committed before process death but later pure Lua processing did not finish
- **THEN** the successor worker MAY call the same key and receive the stored response
- **AND** it SHALL reconstruct subsequent policy without restoring predecessor Lua state

### Requirement: Job completion and failure are terminal and purge sensitive bodies
`Job:complete(optional_result)` and `Job:fail(error)` SHALL commit exactly one terminal outcome. Completion result and failure shall be bounded normalized values used only to derive non-sensitive outcome metadata and SHALL not become durable application history. Once terminal handoff and any separately admitted delayed-playback data are committed, the host SHALL purge work payload, committed effect bodies, and checkpoints and retain only a bounded tombstone containing work/queue/instance identity, FIFO sequence, terminal class, bounded non-sensitive reason, and timing/accounting metadata. Terminal methods called twice or after stale closure SHALL not create another outcome.

#### Scenario: Response is handed to delayed playback
- **WHEN** Lua schedules synthesized response playback and completes the job
- **THEN** the work store SHALL purge transcript and effect bodies after terminal commit
- **AND** the delayed-playback subsystem SHALL independently retain only its admitted payload until its own retirement

#### Scenario: Completed job is inspected after restart
- **WHEN** host projection loads a terminal tombstone
- **THEN** it MAY count and classify the outcome
- **AND** it SHALL not reconstruct user, assistant, tool, secret, or HTTP content

### Requirement: Work epochs distinguish restart from intentional replacement
Each queue SHALL have a durable work epoch. Ordinary process restart with unchanged active package/configuration SHALL preserve the epoch and safely reclaim eligible work. SOS, channel configuration replacement, package revision replacement/rollback/removal, instance deletion, or explicit reset SHALL advance or retire the prior epoch: queued or claimed work with no started effect becomes cancelled, started uncommitted work becomes indeterminate, and terminal tombstones remain bounded. A successor generation SHALL not inherit predecessor queue/job userdata or volatile state.

#### Scenario: Configuration changes with queued turn
- **WHEN** channel configuration is replaced before queued work starts
- **THEN** the predecessor epoch item SHALL be cancelled rather than processed under new configuration
- **AND** the successor SHALL accept new work in a fresh epoch

#### Scenario: Service restarts unchanged
- **WHEN** the process restarts with the same active package/configuration and a safe queued item
- **THEN** a fresh actor SHALL reclaim it under the preserved epoch
- **AND** no predecessor coroutine or handle SHALL survive

### Requirement: Durable work is bounded, isolated, and privately projected
The host SHALL enforce finite queue declarations, nonterminal items, payload/effect bytes, effect count, key bytes, tombstones, concurrent receives, operation deadlines, per-instance/package/process storage, and recovery work. Capacity rejection SHALL occur before mutation. Generic channel projections MAY expose idle/queued/active/failed/indeterminate state, queued count, active presence, and bounded terminal class; they SHALL not expose payloads, effect keys/results, provider content, secret data, or ledger internals. Corruption SHALL isolate the affected queue or package where possible and fail closed without merging partial records.

#### Scenario: Queue capacity is exhausted
- **WHEN** submission would exceed an applicable count or byte bound
- **THEN** it SHALL return `E_BUSY` before durable mutation
- **AND** existing items and sibling queues SHALL remain unchanged

#### Scenario: Work diagnostics are emitted
- **WHEN** an item is submitted, claimed, recovered, or terminalized
- **THEN** diagnostics SHALL contain only bounded identity, phase, counts, and normalized outcome
- **AND** no payload or effect body SHALL be logged

