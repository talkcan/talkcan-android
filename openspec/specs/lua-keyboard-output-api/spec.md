## Purpose

Defines the Lua keyboard-output API module, exposing semantic send text and send key capabilities with execution owner and generation-scoped authorization.

## Requirements

### Requirement: Lua packages declare generic keyboard-output eligibility
A Lua package SHALL obtain keyboard-output authority only by declaring the exact public capability identifier `keyboard.output` in its validated manifest. The host SHALL reserve and inject `talkcan.keyboard_output` for every Lua state, but requiring the module SHALL NOT grant authority. A call from a package that did not declare `keyboard.output` SHALL fail with `E_CAPABILITY_UNDECLARED` before profile lookup, queue admission, connection preparation, keymap compilation, or physical output.

#### Scenario: Declared package requires keyboard-output module
- **WHEN** a package declaring `keyboard.output` requires `talkcan.keyboard_output`
- **THEN** the host SHALL return the reserved module table without consulting package source
- **AND** later operations SHALL remain subject to execution-owner, generation, profile, availability, quota, and call-time authorization checks

#### Scenario: Undeclared package calls keyboard output
- **WHEN** a package that did not declare `keyboard.output` invokes a keyboard-output function
- **THEN** the function SHALL return `(nil, {error = "E_CAPABILITY_UNDECLARED"})`
- **AND** the host SHALL perform no preparation, queue, compilation, transport, or hardware effect

### Requirement: `talkcan.keyboard_output` exposes bounded semantic operations
The `talkcan.keyboard_output` module SHALL expose exactly `send_text(request)` and `send_key(request)` in this contract. `send_text` SHALL accept one exact-key table containing bounded valid-UTF-8 nonempty `text` and nonblank bounded logical `profile`. `send_key` SHALL accept one exact-key table containing logical `profile` and `key`, where `key` SHALL be exactly `enter` or `escape`. The module SHALL NOT expose Android keycodes, USB HID usages, raw operations, modifiers, chords, keymap objects, connection controls, transport frames, arm/disarm/kill operations, acknowledgement tokens, or platform objects.

#### Scenario: Package submits valid text
- **WHEN** an eligible execution owner calls `send_text({text = "hello", profile = "linux:us"})`
- **THEN** the host SHALL validate the complete request before admitting one semantic keyboard-output operation
- **AND** Lua SHALL receive no compiled keys, HID operations, connection object, or acknowledgement identity

#### Scenario: Package submits semantic Enter
- **WHEN** an eligible execution owner calls `send_key({key = "enter", profile = "linux:us"})`
- **THEN** the host SHALL admit at most one semantic Enter operation
- **AND** it SHALL map the key to transport output only inside the host adapter

#### Scenario: Request contains invalid or extra data
- **WHEN** a request is missing a required key, contains an extra key, has a blank or over-bound profile, has empty/invalid/over-bound text, or names a key outside `enter` and `escape`
- **THEN** the call SHALL return a normalized invalid-argument or invalid-value error before operation admission
- **AND** no profile lookup, queue, compilation, connection, or output effect SHALL occur

### Requirement: Keyboard output is authorized by execution owner and live generation
Keyboard-output operations SHALL be permitted only from a host-managed `handle_input` execution owner, a host-managed yield-capable `handle_sos` execution owner, or a runtime-managed task owner belonging to the calling live instance and runtime generation. Current channel selection SHALL NOT be an authorization condition. Deselecting a channel SHALL NOT revoke, cancel, reorder, or redirect its admitted generation-owned keyboard output. Disablement, configuration replacement, package revision replacement/removal, runtime close/failure, or service shutdown SHALL revoke predecessor authority.

#### Scenario: Managed task outputs after deselection
- **WHEN** a live managed task belonging to an eligible generation calls keyboard output after another channel becomes selected
- **THEN** the operation SHALL remain eligible under the original instance and generation
- **AND** it SHALL NOT be redirected to, attributed to, or cancelled by the newly selected channel

#### Scenario: Ineligible callback calls keyboard output
- **WHEN** source evaluation, startup, lifecycle, readiness, or an unmanaged coroutine invokes a keyboard-output function
- **THEN** the call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})` before suspension or effect

#### Scenario: Predecessor task calls after generation replacement
- **WHEN** a managed task from a replaced or closed generation attempts keyboard output
- **THEN** the host SHALL return a closed or stale result
- **AND** it SHALL not admit, resume, or perform a physical operation

### Requirement: Keyboard delivery has typed non-replay terminal outcomes
Each admitted keyboard-output operation SHALL complete exactly once as `delivered`, `rejected`, `failed`, or `indeterminate`. `delivered` SHALL mean complete output was acknowledged. `rejected` SHALL prove no physical output began. `failed` SHALL mean an operation began but the host proves no requested text or key reached the target. `indeterminate` SHALL mean partial delivery cannot be excluded. Lua SHALL receive only bounded stable outcome and reason values; it SHALL NOT receive host operation IDs, acknowledgement IDs, exceptions, endpoints, addresses, compiled keys, or transport details. The host and runtime SHALL NOT automatically replay any terminal operation.

#### Scenario: Complete delivery is acknowledged
- **WHEN** the host confirms the complete requested text or key was delivered
- **THEN** Lua SHALL resume exactly once with a `delivered` result
- **AND** neither host nor runtime SHALL replay the operation

#### Scenario: Request is rejected before output begins
- **WHEN** profile validation, policy, bounds, or keymap compilation proves delivery cannot begin
- **THEN** Lua SHALL resume exactly once with a `rejected` outcome and normalized reason
- **AND** the host SHALL prove no physical output effect began

#### Scenario: Partial output cannot be excluded
- **WHEN** cancellation, timeout, disconnect, acknowledgement loss, transport failure, revocation, or shutdown occurs after output may have begun and the host cannot prove the delivered prefix
- **THEN** Lua SHALL resume at most once with an `indeterminate` outcome while the generation remains resumable
- **AND** the host SHALL attempt terminal safety cleanup exactly once
- **AND** no automatic replay SHALL occur

### Requirement: Host serializes and bounds shared keyboard output
The host SHALL serialize physical keyboard output across all built-in and Lua instances through one bounded host-owned admission policy. Admitted operations SHALL retain instance, generation, and execution-owner identity. The host SHALL enforce finite operation-text, concurrent-operation, queued-operation, and retained-payload bounds per instance, generation, and process. Capacity rejection SHALL occur before physical output begins. One instance SHALL NOT cancel, clean up, reorder, redirect, or receive another instance's outcome, and one producer SHALL NOT allocate an unbounded queue or waiter set.

#### Scenario: Two instances submit output concurrently
- **WHEN** two live authorized instances submit keyboard output concurrently
- **THEN** the host SHALL serialize their physical effects in deterministic admitted order
- **AND** each terminal outcome SHALL return only to its owning execution owner

#### Scenario: Queue capacity is exhausted
- **WHEN** an otherwise valid operation would exceed an applicable queue or retained-payload bound
- **THEN** the host SHALL reject it with `E_BUSY` before compilation, connection, arm, or output begins
- **AND** existing operations and sibling quotas SHALL remain unchanged

#### Scenario: Generation closes with queued work
- **WHEN** a generation closes while its keyboard-output operations are queued but not physically admitted
- **THEN** the host SHALL remove those operations and terminalize them as effect-not-begun cancellation or revocation
- **AND** it SHALL preserve unrelated instances' queue entries and active operation

### Requirement: Keyboard-output content and transport remain private
Keyboard text, semantic keys, logical profiles where sensitive, compiled operations, acknowledgement values, and hardware identities SHALL NOT appear in yielded labels, generic operation metadata, runtime snapshots, structured plugin logs emitted by the host, package-store metadata, or exported acceptance evidence. Host diagnostics SHALL use instance, generation, phase, bounded lengths, and normalized outcomes without content or transport identifiers.

#### Scenario: Host records delivery diagnostics
- **WHEN** a keyboard-output operation is admitted and reaches a terminal outcome
- **THEN** host diagnostics SHALL identify its instance, generation, phase, and normalized outcome
- **AND** diagnostics SHALL NOT contain the text, compiled keys, acknowledgement, address, or device identity
