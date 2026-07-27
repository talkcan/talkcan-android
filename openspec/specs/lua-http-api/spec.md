# lua-http-api Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: Lua packages declare generic HTTPS eligibility
A Lua package SHALL obtain outbound network authority only by declaring the exact public capability identifier `network.http` in its validated manifest. The host SHALL reserve and inject `talkcan.http` for every Lua state, but requiring the module SHALL NOT grant authority. A call by an undeclared package SHALL return `(nil, {error = "E_CAPABILITY_UNDECLARED"})` before URL resolution, DNS, connection creation, secret access, request serialization, or other network effect.

#### Scenario: Declared package requires HTTP
- **WHEN** a package declaring `network.http` requires `talkcan.http`
- **THEN** the runtime SHALL return the host-injected module without consulting package source
- **AND** every request SHALL remain subject to execution-owner, generation, HTTPS, deadline, redirect, size, and quota checks

#### Scenario: Undeclared package attempts networking
- **WHEN** a package without `network.http` calls the HTTP module
- **THEN** the call SHALL return `E_CAPABILITY_UNDECLARED`
- **AND** the host SHALL perform no DNS, connection, TLS, or request effect

### Requirement: `talkcan.http` exposes one bounded request operation
`talkcan.http` SHALL expose exactly `request(request)`. The argument SHALL be an exact-key table containing `method`, `url`, optional `headers`, optional `body`, and optional positive integer `timeout_ms`. `method` SHALL be one of `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, or `DELETE`; `url` SHALL be an absolute normalized HTTPS URL without user information; `headers` SHALL be a bounded string map whose names and values pass host validation; and `body` SHALL be a bounded valid-UTF-8 string. The complete request SHALL be validated before any effect. The API SHALL expose no socket, connection pool, DNS object, certificate object, TLS configuration, file descriptor, streaming source/sink token, Android object, or raw byte buffer.

#### Scenario: Managed task issues valid HTTPS request
- **WHEN** an authorized managed task calls `request` with a valid bounded request
- **THEN** the host SHALL admit one generation-owned HTTPS operation
- **AND** the caller SHALL suspend without retaining an operating-system thread

#### Scenario: Request is malformed
- **WHEN** a request contains an unknown key, unsupported method, invalid URL, invalid header, non-UTF-8 value, or over-bound body
- **THEN** the call SHALL return `E_INVALID_ARGUMENT` or `E_INVALID_VALUE` before DNS or transport work
- **AND** no partial request SHALL be emitted

### Requirement: HTTP authority permits any HTTPS origin but no other transport
An eligible package SHALL be permitted to address any syntactically valid HTTPS origin. The host SHALL NOT require a manifest origin allowlist or per-origin grant. Plain HTTP, non-HTTP schemes, raw TCP or UDP, Unix sockets, local file URLs, user-information URLs, plugin-selected trust anchors, disabled certificate verification, and direct IP transport that violates host URL policy SHALL be rejected. Network and secret eligibility SHALL remain separate declarations; granting one SHALL NOT imply the other.

#### Scenario: Package uses custom compatible endpoint
- **WHEN** an eligible package requests a valid HTTPS URL at a user-configured OpenAI-compatible origin
- **THEN** the host SHALL evaluate it under the same generic network policy as every other HTTPS origin
- **AND** no OpenAI-specific endpoint rule SHALL execute

#### Scenario: Package requests insecure transport
- **WHEN** a package supplies an `http`, `file`, raw-socket, or otherwise unsupported URL
- **THEN** validation SHALL reject it before connection creation
- **AND** the host SHALL NOT downgrade or rewrite it to HTTPS

### Requirement: HTTP responses are complete bounded normalized values
A successful HTTP operation SHALL return `({status = <integer>, headers = <bounded string map>, body = <valid-UTF-8 string>}, nil)` only after the complete accepted response is available. Status SHALL be an integer from 100 through 599. Response headers SHALL be normalized deterministically without exposing connection state, and body bytes SHALL be admitted only when they are valid UTF-8 and fit host response bounds. Protocol status codes, including non-2xx statuses, SHALL be successful transport results rather than host-operation failures. Binary or over-bound bodies SHALL fail atomically without a partial body.

#### Scenario: Server returns non-success status
- **WHEN** an HTTPS server returns a complete bounded `401` response
- **THEN** `request` SHALL return its status, normalized headers, and body as a transport success
- **AND** Lua policy SHALL decide how to interpret the provider response

#### Scenario: Response exceeds bounds
- **WHEN** headers or body exceed host limits or the body is not valid UTF-8
- **THEN** the operation SHALL return a typed `E_TOO_LARGE` or `E_INVALID_VALUE` failure
- **AND** Lua SHALL receive no partial response

### Requirement: TLS, redirects, cancellation, and transport lifecycle are host owned
The host SHALL own DNS, HTTPS connection creation and reuse, platform trust, certificate and hostname validation, redirect processing, transport cancellation, shutdown, and resource cleanup. Redirects SHALL be finite, SHALL remain HTTPS, SHALL be revalidated at every hop, and SHALL NOT forward sensitive request headers across origins unless host policy explicitly proves that forwarding is permitted. Each request SHALL have a finite deadline. Cancellation, timeout, generation replacement, resolver closure, service shutdown, and normal completion SHALL race through one terminal gate; late transport completion SHALL not resume Lua or create another effect.

#### Scenario: Redirect changes origin
- **WHEN** a response redirects to a different HTTPS origin
- **THEN** the host SHALL validate the new URL and bounded redirect count
- **AND** it SHALL remove authorization and other sensitive headers unless safe forwarding is explicitly established

#### Scenario: Generation closes during request
- **WHEN** an owning generation closes while HTTPS work is pending
- **THEN** its suspended execution SHALL be discarded and transport work cancelled or detached
- **AND** a late response SHALL be stale and SHALL NOT re-enter Lua

### Requirement: HTTP calls are restricted to yield-capable owners
`talkcan.http.request` SHALL be callable only from a live host-managed input owner, runtime-managed task owner, durable-work effect owner, or short-lived dynamic-choice resolver owner whose package declared `network.http`. Calls from module evaluation SHALL fail the module load through the existing effect guard. Calls from startup, lifecycle, readiness, SOS, unmanaged coroutines, or closed/stale owners SHALL return the applicable context or lifecycle error before network effect.

#### Scenario: Dynamic resolver calls HTTP
- **WHEN** a live bounded resolver with declared network eligibility requests HTTPS
- **THEN** the resolver coroutine SHALL suspend and resume under its resolver deadline and authority
- **AND** no channel runtime or durable work item SHALL be created

#### Scenario: Startup calls HTTP
- **WHEN** synchronous channel startup attempts an HTTP request
- **THEN** the call SHALL return `E_INVALID_CONTEXT`
- **AND** startup SHALL not suspend or start network work

### Requirement: HTTP accounting and diagnostics are bounded and content private
The host SHALL enforce finite per-request, per-owner, per-generation, per-package, and process bounds for concurrent requests, queued requests, URL/header/body bytes, response bytes, redirect count, and retained payloads. Capacity exhaustion SHALL return `E_BUSY` before network effect. Host diagnostics MAY include package/instance identity, method, normalized origin classification, phase, byte counts, status class, and terminal outcome; they SHALL NOT contain URL query values, headers, request or response bodies, secrets, credentials, tool arguments, or provider payloads.

#### Scenario: Network capacity is exhausted
- **WHEN** admitting a request would exceed an applicable count or retained-byte quota
- **THEN** the call SHALL return `E_BUSY` before DNS or connection creation
- **AND** existing operations and sibling quotas SHALL remain unchanged

#### Scenario: Host records request completion
- **WHEN** an HTTPS request reaches a terminal outcome
- **THEN** diagnostics SHALL contain only bounded attribution, phase, counts, and normalized outcome
- **AND** no secret-bearing or application content SHALL be recorded

