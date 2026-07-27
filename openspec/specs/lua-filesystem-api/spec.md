## Purpose

Defines the bounded yielding `talkcan.fs` document-tree API over mount handles: confined logical relative paths, directory creation and portable metadata, paginated listing, bounded complete UTF-8 reads and writes, explicit nonrecursive removal, a fixed portable failure model, and generation-safe exactly-once effects.

## Requirements

### Requirement: `talkcan.fs` exposes only bounded document-tree operations
The host SHALL reserve and inject `talkcan.fs` for every package. The module SHALL expose exactly `mount`, `mkdir`, `stat`, `list`, `read_text`, `write_text`, and `remove`. Requiring the module SHALL NOT grant storage eligibility or a mount binding. `mount` SHALL be a bounded synchronous lookup; every I/O function SHALL be yielding and callable only from `handle_input` or a runtime-managed task. Source/module evaluation SHALL fail on any function call through the effect-call-during-load guard, and ineligible callbacks or unmanaged coroutines SHALL receive `E_INVALID_CONTEXT` before suspension or effect.

#### Scenario: Managed task performs filesystem work
- **WHEN** a package declaring `storage.files` calls an I/O function from a runtime-managed task with a live writable mount
- **THEN** the coroutine SHALL yield while the host performs the bounded operation
- **AND** it SHALL resume exactly once with a normalized success or error pair

#### Scenario: Readiness callback attempts filesystem I/O
- **WHEN** `handle_readiness` calls `fs.list` or another I/O function
- **THEN** the call SHALL return `(nil, {error = "E_INVALID_CONTEXT"})`
- **AND** no provider access SHALL begin

### Requirement: Logical paths are confined to one mount
Every filesystem path SHALL be a valid UTF-8 relative logical path using `/` only as the separator. The empty string SHALL identify the supplied mount root only for `fs.list`; every other operation SHALL reject it. The host SHALL reject absolute paths, empty or repeated components, `.`, `..`, NUL, backslash, invalid UTF-8, over-bound component count, over-bound component bytes, and over-bound total bytes before provider access. Operations SHALL resolve all components beneath the supplied mount and SHALL NOT cross mounts or intentionally follow aliases outside the granted tree. Packages SHALL NOT rely on case sensitivity, Unicode-normalization distinctions, symlinks, hard links, inodes, ownership, permission bits, devices, or executable semantics.

#### Scenario: Path attempts parent traversal
- **WHEN** Lua submits `entries/../../outside` or an absolute/platform path
- **THEN** validation SHALL return `E_INVALID_PATH` before mount resolution or I/O

#### Scenario: Valid nested logical path is used
- **WHEN** Lua submits canonical bounded components below a live mount
- **THEN** the platform adapter SHALL resolve the path only within that mount
- **AND** no platform-native location SHALL be returned to Lua

#### Scenario: Mount root is listed
- **WHEN** Lua calls `fs.list` with the empty-string root selector and a live mount
- **THEN** the platform adapter SHALL list the supplied mount root
- **AND** another filesystem operation receiving an empty path SHALL return `E_INVALID_PATH` before provider access

### Requirement: Directory creation and metadata are portable
`fs.mkdir(mount, path, options)` SHALL accept an exact-key options table containing required Boolean `parents` and return status `created` or `existing`; an existing non-directory SHALL fail with `E_NOT_DIRECTORY` or `E_EXISTS`. `fs.stat(mount, path)` SHALL return exact portable fields `kind` (`file` or `directory`) and, for files, nonnegative `size`; it MAY return bounded `modified_at_unix_ms` when the provider supplies it. It SHALL NOT expose inode, device, owner, mode, native identifier, absolute path, URI, URL, or provider object.

#### Scenario: Parent directories are created
- **WHEN** `mkdir` receives a valid path with `{parents=true}` and missing ancestors
- **THEN** it SHALL create the required directory hierarchy or return a typed terminal error without escaping the mount

#### Scenario: File metadata is read
- **WHEN** `stat` succeeds for a regular document
- **THEN** Lua SHALL receive portable kind and size data only

### Requirement: Directory listing is paginated and unordered
`fs.list(mount, path, options)` SHALL accept an exact-key options table with bounded positive `limit` and optional opaque `cursor`. It SHALL return at most the accepted limit of exact `{name, kind}` entries and optional `next_cursor`. Ordering SHALL be unspecified. Cursors SHALL be unforgeable, state-, generation-, mount-, directory-, and listing-session-bound, nonpersistent, and invalid after completion, failure, replacement, or close. The host SHALL apply finite limits to page size, returned name bytes, total response bytes, and operation duration.

#### Scenario: Directory requires multiple pages
- **WHEN** a directory contains more entries than the accepted page limit
- **THEN** the host SHALL return one bounded page and an opaque continuation cursor
- **AND** a valid next call SHALL continue the same listing without returning an unbounded array

#### Scenario: Cursor belongs to another mount
- **WHEN** Lua presents a cursor with a different mount, path, state, generation, or listing session
- **THEN** listing SHALL return `E_STALE` or `E_INVALID_ARGUMENT` before provider traversal

### Requirement: Text reads and writes are bounded and complete
`fs.read_text(mount, path, options)` SHALL accept an exact-key options table containing bounded positive `max_bytes`, read at most the lower of that value and host policy, require valid UTF-8, and return exact `{text, bytes}` only after the complete accepted document is read. It SHALL return `E_TOO_LARGE` without a partial string when the document exceeds the bound. `fs.write_text(mount, path, text, options)` SHALL accept exact mode `create-new` or `replace`, validate bounded UTF-8 before effect, and return exact `{status="written", bytes}` only when the complete supplied content is visible. `create-new` SHALL not knowingly overwrite an existing document. `replace` SHALL NOT promise crash-atomicity across all providers.

#### Scenario: Bounded UTF-8 document is read
- **WHEN** a file is valid UTF-8 and fits the requested and host bounds
- **THEN** `read_text` SHALL return the complete text and exact UTF-8 byte count

#### Scenario: Read grows beyond bound
- **WHEN** provider content exceeds the accepted maximum during reading
- **THEN** the operation SHALL fail with `E_TOO_LARGE`
- **AND** Lua SHALL receive no partial text

#### Scenario: Create-new destination exists
- **WHEN** `write_text` uses `create-new` for an existing destination
- **THEN** it SHALL return `E_EXISTS`
- **AND** it SHALL leave the existing content unchanged

#### Scenario: Replace succeeds
- **WHEN** `write_text` in `replace` mode returns success
- **THEN** the complete supplied text SHALL be visible at the destination
- **AND** the result SHALL make no stronger cross-provider crash-atomicity claim

### Requirement: Removal is explicit and nonrecursive
`fs.remove(mount, path, options)` SHALL accept an exact-key options table containing `missing_ok` and SHALL remove exactly one file or one empty directory. It SHALL NOT recursively traverse, delete a nonempty directory, cross a mount, or follow provider aliases outside the granted tree. Missing paths SHALL return `E_NOT_FOUND` unless `missing_ok=true`, in which case the operation SHALL return exact status `missing`.

#### Scenario: Package removes a spool file
- **WHEN** an authorized managed task removes one existing file
- **THEN** the host SHALL remove only that addressed document and return status `removed`

#### Scenario: Package requests recursive deletion implicitly
- **WHEN** the addressed path is a nonempty directory
- **THEN** removal SHALL fail with `E_IS_DIRECTORY`, `E_BUSY`, or another documented portable refusal
- **AND** descendants SHALL remain unchanged

### Requirement: Filesystem operations use a fixed portable failure model
Expected filesystem failures SHALL return `(nil, error_table)` with stable `error` and optional bounded language-neutral `reason`. Codes SHALL be exactly `E_INVALID_ARGUMENT`, `E_INVALID_PATH`, `E_INVALID_CONTEXT`, `E_CAPABILITY_UNDECLARED`, `E_MOUNT_UNAVAILABLE`, `E_REAUTHORIZATION_REQUIRED`, `E_READ_ONLY`, `E_NOT_FOUND`, `E_EXISTS`, `E_NOT_DIRECTORY`, `E_IS_DIRECTORY`, `E_TOO_LARGE`, `E_NO_SPACE`, `E_BUSY`, `E_TIMEOUT`, `E_CANCELLED`, `E_CLOSED`, `E_STALE`, `E_UNSUPPORTED`, or `E_IO`. Unknown platform failures SHALL collapse to `E_IO`. Reasons SHALL NOT contain platform exceptions, paths, URIs, document IDs, URLs, bookmarks, provider accounts, or device identities.

#### Scenario: Platform provider throws an unknown failure
- **WHEN** the Android or future platform adapter receives a provider-specific failure without a stable portable mapping
- **THEN** Lua SHALL receive `E_IO` with no platform-sensitive detail
- **AND** unrelated mounts, instances, and operations SHALL remain usable

### Requirement: Filesystem effects are generation-safe and exactly once
Each admitted I/O request SHALL carry the current state, instance, generation, execution owner, operation ID, declared eligibility, mount token, and one atomic terminal gate. Success, typed provider failure, deadline, explicit input cancellation, managed-task cancellation, binding revocation, generation close, and process teardown SHALL race through that gate. A live cancelled execution MAY resume once with `E_CANCELLED`; generation close SHALL discard suspended work without Lua re-entry. Late completion SHALL not publish text, mutate successor state, reuse a grant under another instance, or produce a second result.

#### Scenario: Generation closes during a write
- **WHEN** a generation closes while `write_text` is suspended
- **THEN** the host SHALL cancel or detach provider work, discard the coroutine, invalidate the mount handle, and suppress late Lua completion
- **AND** successor code SHALL not inherit the operation or cursor
