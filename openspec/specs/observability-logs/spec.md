## Purpose

Defines bounded application and plugin logging, persistent storage, reactive publication, and Log Analysis filtering for operational diagnostics.

## Requirements

### Requirement: Unified Log Interception and Logcat mirroring
The system SHALL intercept log statements from all core application modules and write them to both standard Android Logcat and an in-app persistent log storage.

#### Scenario: Log written to Logcat and disk buffer
- **WHEN** a component writes a message using the logging subsystem
- **THEN** the system SHALL output that message to standard Android Logcat
- **AND** write it to the persistent circular log file

### Requirement: Disk-backed persistent circular log storage
The log storage SHALL be persistent, surviving application restarts, and SHALL be bounded in size to prevent unlimited storage growth by using a circular buffer (e.g., rotating files or fixed line capacity).

#### Scenario: Logs survive application restarts
- **WHEN** the application is restarted and the user opens the Log Analysis screen
- **THEN** the system SHALL load and display log entries captured before the restart

#### Scenario: Bounded log storage size
- **WHEN** the total size of stored logs exceeds the configured limit
- **THEN** the system SHALL evict the oldest log entries to make room for new ones

### Requirement: Reactive Log Stream publication
The background service SHALL publish log entry changes through a reactive `StateFlow` to the UI layer for real-time log inspection.

#### Scenario: Live updates on the log screen
- **WHEN** the Log Analysis screen is open and a new log entry is written by the application
- **THEN** the screen SHALL update to display the new entry in real-time

### Requirement: Log Analysis view with Level, Tag, and Search filtering
The `LogAnalysisScreen` SHALL provide interactive controls to search log messages by text, filter logs by log severity levels, and filter by tag names.

#### Scenario: Filtering logs by level
- **WHEN** the user selects the "Warn" level filter
- **THEN** the screen SHALL display only log entries with severity Warn or Error

#### Scenario: Searching logs by text
- **WHEN** the user enters a search query
- **THEN** the screen SHALL display only log entries containing the query text

### Requirement: Dynamic log level configuration at runtime
The user/developer SHALL be able to adjust the minimum logging level globally or per component tag at runtime.

#### Scenario: Changing active log level
- **WHEN** the user adjusts the runtime log level threshold for a tag
- **THEN** log messages for that tag with severity below the new threshold SHALL be ignored and NOT written to the log buffer

### Requirement: In-app log formatting and font scaling
The log screen SHALL support switching between Compact and Detailed view modes, and SHALL allow the developer to scale the font size up or down for readability.

#### Scenario: Toggling compact mode
- **WHEN** the user selects Compact view format
- **THEN** the screen SHALL hide extra metadata to show more messages in a single line

### Requirement: Persistent diagnostics attribute PTT cancellation provenance
The system SHALL write machine-readable persistent diagnostics for source-specific and global PTT cancellation requests and their terminal outcomes. Diagnostics SHALL identify the semantic caller, requested source, current session source and phase when present, disposition, terminal claim category, and non-sensitive reason. RSM serial attempt termination SHALL record whether the attempt ever connected and the reconnect disposition. Diagnostics SHALL NOT include Bluetooth hardware addresses, PCM or encoded audio payloads, transcript content, credentials, or channel message content.

#### Scenario: Source-scoped cancellation is accepted
- **WHEN** a source-specific cancellation matches the current session owner
- **THEN** persistent logs SHALL record the semantic caller, requested source, active source and phase, accepted disposition, and reason
- **AND** terminal diagnostics SHALL identify cancellation as the claimed terminal category

#### Scenario: Cross-source cancellation is rejected
- **WHEN** a source-specific cancellation does not match the current session owner
- **THEN** persistent logs SHALL record a rejected source-mismatch disposition with both semantic sources
- **AND** SHALL NOT expose hardware identity or captured content

#### Scenario: Cancellation finds no active session
- **WHEN** a source-specific cancellation is requested with no current session
- **THEN** persistent logs SHALL record a no-active-session disposition

#### Scenario: RSM reconnect attempt terminates
- **WHEN** an automatic or manual RSM SPP connection attempt ends
- **THEN** persistent logs SHALL record whether the attempt connected, whether monitoring remains requested, and whether reconnect was scheduled, blocked, or stopped
- **AND** SHALL correlate any RSM cancellation request using semantic source and session identifiers rather than Bluetooth address

#### Scenario: Audio session publishes terminal completion
- **WHEN** an audio input session finishes terminal processing
- **THEN** persistent logs SHALL record session identifier, source, terminal claim category, semantic reason, and cleanup failure categories if any
- **AND** SHALL omit audio and channel content

### Requirement: Plugin structured logs enter unified observability under bounded host policy
The logging subsystem SHALL accept host-attributed structured records emitted by live Lua channel generations through a dedicated bounded plugin sink. Each projected record SHALL map `debug`, `info`, `warn`, and `error` to the corresponding host severity; use the fixed host-owned tag `LuaChannel`; preserve the host timestamp; and encode a canonical bounded message containing the channel instance ID, runtime generation, and normalized plugin payload. Plugin code SHALL NOT control the host tag, severity mapping, timestamp, instance ID, generation, throwable, persistence destination, or log-level policy. Successfully projected plugin records SHALL be mirrored to Android Logcat, persisted in the existing rotating log store, published through the existing reactive log stream, and viewable through the existing Log Analysis screen.

The plugin sink SHALL have finite admission and message-size bounds and SHALL never use an unbounded overflow queue. Records rejected or rate-dropped by `talkcan.log` SHALL not reach it. When sink capacity, canonical serialization, disk persistence, or shutdown prevents projection, the host SHALL drop the affected projection without blocking or re-entering Lua, SHALL retain at most bounded host-owned loss diagnostics, and SHALL not recursively include the rejected plugin payload.

#### Scenario: Accepted plugin info record is projected
- **WHEN** a live Lua generation emits an accepted `info` record with a valid normalized payload
- **THEN** the logging subsystem SHALL emit at most one `Info` entry tagged `LuaChannel` with host-attributed instance and generation fields
- **AND** SHALL mirror, persist, stream, and display it through the existing observability surfaces

#### Scenario: Plugin record is filtered by host level
- **WHEN** the configured global or `LuaChannel` tag threshold excludes the mapped plugin severity
- **THEN** the record SHALL be omitted from Logcat, persistence, and the reactive log stream under the ordinary host filtering policy
- **AND** SHALL NOT alter the plugin's already completed `talkcan.log` result

#### Scenario: Plugin sink reaches capacity
- **WHEN** accepted plugin records arrive faster than the bounded observability sink can process them
- **THEN** the sink SHALL reject excess projections immediately and retain at most bounded loss accounting
- **AND** SHALL NOT suspend Lua, allocate an unbounded waiter or record queue, or displace the runtime generation's lifecycle work

#### Scenario: Plugin record survives application restart
- **WHEN** a projected plugin record was committed to the rotating persistent log before application shutdown
- **THEN** the Log Analysis screen SHALL load it after restart with its original timestamp, level, tag, instance, generation, and canonical payload text
- **AND** SHALL NOT imply that the originating Lua generation was restored

### Requirement: Plugin observability excludes executable and sensitive host data
Plugin log projection SHALL contain only the normalized payload accepted by `talkcan.log` and host-generated attribution. The host SHALL NOT enrich a plugin record with Lua source, package archive bytes or paths, full artifact digests, repository credentials, channel configuration, host credentials, captured or encoded audio, transcripts, message content not explicitly supplied in the accepted payload, Bluetooth addresses, Android objects, native handles, stack traces, or capability objects. Canonical serialization SHALL be deterministic and bounded and SHALL reject rather than truncate, coerce, or partially persist a payload that cannot fit the sink contract.

#### Scenario: Plugin attempts to log a disallowed runtime value
- **WHEN** a payload contains userdata, a function, a coroutine, a native handle, a cycle, an invalid string, or another value rejected by Lua normalization
- **THEN** no corresponding persistent or reactive host log SHALL be created
- **AND** observability SHALL NOT stringify the value, inspect the platform object, or log a partial payload

#### Scenario: Host attribution is attached
- **WHEN** a valid plugin payload is projected
- **THEN** the host SHALL attach only the semantic channel instance ID, runtime generation, timestamp, and mapped level required by the log contract
- **AND** SHALL NOT attach package-store internals, capability identities, or device identifiers

### Requirement: Log Analysis can isolate Diagnostics Channel activity
The Log Analysis surface SHALL allow plugin records to be isolated through its existing level, tag, and text-search controls. A user SHALL be able to filter by the fixed `LuaChannel` tag and search canonical message text for a diagnostics instance identifier, generation, event name, or release marker without a diagnostics-specific screen or parser. Clearing logs and changing tag thresholds SHALL use the existing observability actions and SHALL NOT mutate the installed package, catalogue definition, or Lua runtime.

#### Scenario: User filters plugin records
- **WHEN** the user selects the `LuaChannel` tag and searches for a Diagnostics Channel event name
- **THEN** the view SHALL show matching projected plugin records in timestamp order
- **AND** SHALL omit unrelated core and plugin records that do not satisfy the active filters

#### Scenario: User clears logs
- **WHEN** the user clears the Log Analysis store while a Diagnostics Channel generation remains live
- **THEN** existing persisted and in-memory entries SHALL be removed according to the ordinary clear contract
- **AND** subsequent accepted Diagnostics Channel records SHALL continue to appear without restarting the package runtime