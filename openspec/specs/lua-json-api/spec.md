# lua-json-api Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Runtime injects a capability-free bounded JSON module
The host SHALL reserve and inject `talkcan.json` for every Lua state and short-lived resolver state. Requiring it SHALL NOT require or grant an effect capability because encoding and decoding are state-local computation. Package source SHALL NOT define or shadow `talkcan.json`. Calling the module during source evaluation SHALL be permitted only for pure encoding or decoding that satisfies module-load execution and allocation bounds; it SHALL perform no host I/O or persistent mutation.

#### Scenario: Package requires JSON
- **WHEN** a package requires `talkcan.json`
- **THEN** the runtime SHALL return the injected state-local module without consulting package source
- **AND** no network, filesystem, secret, profile, or work capability SHALL be acquired

#### Scenario: Package shadows JSON module
- **WHEN** package source declares `talkcan.json`
- **THEN** static source-map validation SHALL reject the complete program image

### Requirement: `talkcan.json` exposes exact encode and decode operations
`talkcan.json` SHALL expose exactly `encode(value)`, `decode(text)`, and immutable field `null`. `encode` SHALL accept one JSON-compatible normalized Lua value and return `(json_text, nil)` or `(nil, error_table)`. `decode` SHALL accept one bounded valid-UTF-8 string containing exactly one JSON value with no trailing non-whitespace data and return `(value, nil)` or `(nil, error_table)`. Neither operation SHALL yield, access platform I/O, mutate input tables, or return parser/encoder objects.

#### Scenario: Lua value is encoded
- **WHEN** Lua passes a valid bounded map containing strings, booleans, integers, arrays, and `json.null`
- **THEN** `encode` SHALL return one valid UTF-8 JSON document preserving those JSON value classes

#### Scenario: JSON document is decoded
- **WHEN** Lua passes a valid bounded JSON object
- **THEN** `decode` SHALL return a detached Lua value containing no host or parser references

#### Scenario: Input contains trailing document
- **WHEN** decode input contains two JSON values or non-whitespace data after the first value
- **THEN** the whole call SHALL fail with `E_INVALID_VALUE`
- **AND** it SHALL return no partial decoded value

### Requirement: JSON values map deterministically to Lua values
JSON strings SHALL map to valid-UTF-8 Lua strings; booleans to booleans; arrays to contiguous 1..n plain tables; objects to plain string-keyed tables; and null to the exact state-local `talkcan.json.null` sentinel. JSON integer tokens representable exactly as signed 64-bit integers SHALL map to Lua integers. Other finite JSON number tokens representable by the runtime SHALL map to finite Lua numbers. Encoding SHALL preserve Lua integer tokens as JSON integers and SHALL reject non-finite numbers, mixed or sparse tables, non-string object keys, cycles, metatables other than the locked null sentinel, functions, threads, and unsupported userdata.

#### Scenario: Integer remains an integer
- **WHEN** Lua encodes signed integer `42`
- **THEN** the emitted JSON token SHALL be `42` rather than `42.0`
- **AND** decoding that token SHALL produce a Lua integer

#### Scenario: JSON null round trips
- **WHEN** a document contains null in an object or array
- **THEN** decode SHALL place the exact state-local `json.null` sentinel at that position
- **AND** re-encoding the decoded value SHALL emit JSON null at the same position

#### Scenario: Invalid Lua table is encoded
- **WHEN** a value contains a cycle, sparse array, mixed keys, function, thread, or unsupported userdata
- **THEN** encode SHALL reject the whole value with `E_INVALID_VALUE`
- **AND** it SHALL emit no partial document

### Requirement: JSON null is opaque, state-local, and non-exportable outside JSON paths
`talkcan.json.null` SHALL be a host-created immutable state-local opaque sentinel with locked metatable and equality only to itself. It MAY appear in values consumed or produced by `talkcan.json` and in package-local computation. Ordinary callback returns, logs, configuration payloads, durable work payloads/effect results, profile fields, and other normalized host-operation arguments SHALL reject a value containing the sentinel with `E_INVALID_VALUE` unless the receiving API explicitly declares JSON-value support. A sentinel from another state or generation SHALL be foreign and invalid.

#### Scenario: Null sentinel is logged
- **WHEN** Lua nests `json.null` in a structured log payload
- **THEN** the log call SHALL reject the entire payload with `E_INVALID_VALUE`
- **AND** it SHALL not stringify or silently replace the sentinel

#### Scenario: Null sentinel crosses actor state
- **WHEN** a value from one state attempts to present its null sentinel to another state
- **THEN** the runtime SHALL reject it as foreign or invalid

### Requirement: JSON processing is strictly bounded
The host SHALL enforce finite input bytes, output bytes, nesting depth, aggregate entries, object-key bytes, string bytes, numeric-token bytes, and active execution bounds. Encoding or decoding that would exceed a bound SHALL stop with `E_TOO_LARGE`, release temporary allocations, and return no partial result. Allocation remains charged to the owning Lua state. A malformed or adversarial document SHALL not affect another actor or the Android process.

#### Scenario: Document nesting exceeds limit
- **WHEN** decode input contains valid JSON nested beyond the configured maximum depth
- **THEN** decode SHALL return `E_TOO_LARGE`
- **AND** the owning state SHALL remain usable unless its independent actor memory policy has failed

#### Scenario: Encoded output exceeds limit
- **WHEN** a valid Lua value would encode beyond the output-byte bound
- **THEN** encode SHALL return `E_TOO_LARGE` without returning a prefix

### Requirement: Package-local `cjson` compatibility is not a host compatibility promise
A package MAY vendor a non-reserved pure-Lua module named `cjson` that delegates to `talkcan.json` and exposes only the upstream behavior required by its vendored libraries. The host SHALL NOT reserve `cjson`, `socket.*`, `ltn12`, or other ecosystem module names, SHALL NOT resolve them outside the immutable package source map, and SHALL NOT claim general Lua CJSON ABI compatibility. Native `lua-cjson` SHALL remain prohibited.

#### Scenario: OpenAI package requires vendored cjson
- **WHEN** the external package requires its package-local `cjson` adapter
- **THEN** ordinary source-map resolution SHALL load that exact vendored Lua module
- **AND** the adapter MAY use `talkcan.json` without loading a native module

#### Scenario: Package omits cjson dependency
- **WHEN** package code requires `cjson` but its source map lacks that module
- **THEN** require SHALL return the normal package-local module-not-found failure
- **AND** the host SHALL not supply or download a fallback

