# lua-openai-agent-channel Specification

## Purpose
TBD - created by archiving change enable-lua-openai-agent-channel. Update Purpose after archive.
## Requirements
### Requirement: OpenAI Agent is an external source-only Lua package
The Lua OpenAI Agent SHALL be published from the official `talkcan-channels/openai-agent` GitHub repository as a canonical `talkcan-channel.zip` stable release satisfying the current exact package-format-v1 contract. Its manifest SHALL bind to the host-resolved durable repository database ID, declare the exact Lua/API versions, profile type, dynamic resolver, `turns` work queue, resources, configuration, and required capabilities, and contain only `manifest.json` plus canonical UTF-8 Lua modules. The package SHALL not be bundled, automatically installed, specially registered, or resolved by mutable repository coordinates.

#### Scenario: Published artifact is inspected
- **WHEN** the host inspects the official release asset
- **THEN** static validation SHALL derive one ordinary installed-provider identity from the resolved repository ID
- **AND** the archive SHALL contain no native library, bytecode, secret, generated credential, or unrecognized file

### Requirement: Package vendors one exact off-the-shelf OpenAI client
The package SHALL vendor the Lua sources of `leafo/lua-openai` tag `v1.8.0` at commit `d363da696a602b0a966d3942777e587c552363ba` with required license notices. It SHALL vendor the exact pure-Lua `tableshape`, `ltn12`, and `socket.url` dependencies selected by the package and package-local `socket.http` and `cjson` adapters over `talkcan.http` and `talkcan.json`. It SHALL not modify the OpenAI client's protocol policy merely to reproduce it locally, load LuaRocks at runtime, require native LuaSocket/LuaSec/CJSON, use C modules, own TLS, or fall back to a host OpenAI SDK.

#### Scenario: Client performs request
- **WHEN** package policy calls the vendored client's Chat Completions method
- **THEN** its LuaSocket-style request SHALL pass through the package adapter to `talkcan.http`
- **AND** JSON SHALL pass through the package adapter to `talkcan.json`

#### Scenario: Dependency module is absent
- **WHEN** an exact vendored dependency is missing or fails source-map validation
- **THEN** package validation or module loading SHALL fail closed
- **AND** the host SHALL not download or supply a substitute

### Requirement: Package declares reusable OpenAI-compatible profiles
The package SHALL declare repository-scoped profile type `openai_compatible` with bounded non-secret `base_url` string and protected required `api_key` secret. Users SHALL create named global profiles of this type through generic profile management. Multiple Lua OpenAI Agent instances MAY select the same profile while keeping independent model, prompt, keyboard, queue, conversation, and runtime state. Existing built-in OpenAI profiles SHALL remain separate and SHALL not be imported, copied, aliased, or exposed to the package in this change.

#### Scenario: User creates Lua profile
- **WHEN** the user saves a valid HTTPS base URL and API key for the package type
- **THEN** generic profile storage SHALL persist the scalar URL and protected secret reference
- **AND** Lua channel configuration SHALL later select only the stable profile ID

#### Scenario: Built-in profile already exists
- **WHEN** an equivalent legacy built-in OpenAI profile exists
- **THEN** the Lua package SHALL not enumerate or consume it
- **AND** side-by-side evaluation SHALL require an independently created package profile

### Requirement: Agent instances configure profile, discovered model, prompt, and keyboard independently
Each instance SHALL persist exact fields for selected package profile ID, model ID, multiline system prompt, Keyboard-tools enabled state, and the generic keyboard platform/layout/profile scalar chain used when tools are enabled. The model field SHALL be a package dynamic choice depending on the selected OpenAI-compatible profile and resolved by the package's model resolver. Configuration SHALL contain no base URL, plaintext API key, profile object, model response, HTTP object, OpenAI client, queue object, or tool object. Sibling instances SHALL remain isolated.

#### Scenario: Two agents share one profile
- **WHEN** two instances select one package profile
- **THEN** each SHALL independently retain its model, system prompt, keyboard settings, work queue, and conversation
- **AND** changing one instance SHALL not mutate the sibling

#### Scenario: Multiline prompt is saved
- **WHEN** the user saves a prompt containing line breaks
- **THEN** configuration persistence and startup snapshot SHALL preserve the exact bounded UTF-8 text and line breaks

### Requirement: Model choices are discovered by package Lua rather than Kotlin
The package SHALL declare a short-lived resolver that resolves only the selected profile, reads its granted API key, calls the profile's HTTPS `/models` operation through the vendored client or package adapter, and returns bounded unique model IDs and display labels. Kotlin and Rust SHALL not contain OpenAI endpoint paths, request/response models, pagination logic, model filtering, credential injection, or SDK client code for this resolver. A missing, unauthorized, unreachable, malformed, timed-out, or stale profile/model result SHALL preserve configuration and expose a typed unavailable field state without choosing another model.

#### Scenario: Models endpoint succeeds
- **WHEN** the resolver receives a current available profile and valid bounded model response
- **THEN** the editor SHALL show its normalized model choices for that exact profile revision
- **AND** the persisted channel value SHALL be only the chosen model ID

#### Scenario: Models endpoint fails
- **WHEN** HTTPS, authentication, JSON, provider response, bounds, or resolver execution fails
- **THEN** model choices SHALL be unavailable with a bounded non-secret reason
- **AND** no Kotlin OpenAI discovery capability SHALL be used as fallback

### Requirement: Readiness validates all generic dependencies without remote completion
An enabled instance SHALL report ready only when its selected package profile and model resolve currently, required `network.http`, `secrets.read`, `work.queue`, transcription, synthesis, and playback authorities are available, its `turns` queue can admit work, and every enabled keyboard reference and `keyboard.output` authority is available. Readiness MAY use the bounded model-choice resolver but SHALL not submit a Chat Completion, start PTT capture, create a user turn, type output, synthesize audio, or expose plaintext secrets in readiness data.

#### Scenario: All dependencies are available
- **WHEN** profile/model, audio, work, network, secret, and enabled keyboard dependencies validate
- **THEN** the instance SHALL report ready and become eligible for input preparation

#### Scenario: Required dependency is unavailable
- **WHEN** any required dependency is missing, denied, stale, or unavailable
- **THEN** the channel SHALL refuse new capture with a typed semantic reason
- **AND** it SHALL not submit remote or keyboard effects

### Requirement: Released PTT audio becomes a durable Lua work item
`handle_input` SHALL transcribe one nonempty committed Recording through `talkcan.transcription`, trim and validate the bounded transcript, and submit `{schema_version=1, user_text=<text>}` to the declared `turns` queue. Queue submission success SHALL durably commit the turn before the callback returns `{ok=true}`. The callback SHALL not wait for Chat Completion, tool execution, synthesis, or playback and SHALL not transfer or retain the Recording, audio route, PTT callback, secret, client, or queue Job.

#### Scenario: Valid transcript is accepted
- **WHEN** local transcription returns nonblank bounded text and queue admission succeeds
- **THEN** input SHALL return success after durable admission
- **AND** the startup worker SHALL process it independently of the PTT route

#### Scenario: Transcription or admission fails
- **WHEN** audio is empty, transcription fails, text is invalid, or queue capacity is unavailable
- **THEN** input SHALL return a typed application failure
- **AND** no remote request or partial queue item SHALL be created

### Requirement: One managed worker serializes turns for each instance
Successful startup SHALL open the declared `turns` queue and admit one runtime-managed worker. The worker SHALL block cooperatively on `Queue:receive`, process at most one Job at a time in FIFO order, and allow later turns to remain durably queued. Different agent instances MAY progress independently. Worker termination, generation replacement, SOS, shutdown, queue failure, and task failure SHALL follow generic work and actor rules without retaining an operating-system thread.

#### Scenario: Second turn arrives during completion
- **WHEN** a second transcript is admitted while the first Job is active
- **THEN** it SHALL remain in FIFO order without starting a concurrent run for that queue
- **AND** input admission SHALL not wait for the first Job

### Requirement: Each generation owns one volatile conversation
The package SHALL keep one volatile conversation per live instance generation, beginning with the configured system prompt and appending successful user, assistant, tool-call, and tool-result values in order. Completed sequential Jobs in one generation SHALL share that conversation. Process restart, SOS, configuration replacement, package replacement/rollback/removal, or runtime closure SHALL discard it. Durable work payloads/effects MAY resume an active safe Job but completed prior Jobs and terminal tombstones SHALL not be loaded as conversation context.

#### Scenario: Sequential turns share context
- **WHEN** two Jobs complete in one unchanged generation
- **THEN** the second request SHALL include successful conversation values from the first
- **AND** no sibling instance's values SHALL appear

#### Scenario: Process restarts
- **WHEN** the service reconstructs an unchanged instance with safe queued work
- **THEN** the new Lua state SHALL begin a fresh conversation
- **AND** generic work recovery SHALL not restore the predecessor Lua session

### Requirement: Package uses bounded non-streaming Chat Completions
The package SHALL use the vendored client's non-streaming Chat Completions API with the selected model, exact conversation, declared tools, and `parallel_tool_calls=false`. It SHALL not use the Responses API, Assistants API, Realtime API, server-side conversation state, streaming tokens, streaming playback, or profile-selectable protocol modes. Each Job SHALL enforce finite model turns, tool calls, user/request/assistant bytes, per-effect deadlines, and total policy work. Chat Completion calls SHALL execute through stable `Job:effect` keys so committed normalized responses are memoized and ambiguous submissions are never replayed.

#### Scenario: Completion returns assistant text
- **WHEN** a bounded response contains final nonblank assistant text and no tool calls
- **THEN** Lua SHALL append it to the volatile conversation and proceed to synthesis/playback

#### Scenario: Completion effect is interrupted ambiguously
- **WHEN** the process or generation terminates after the effect-start marker and before its result commit
- **THEN** generic work recovery SHALL mark the Job indeterminate
- **AND** the package SHALL not resubmit that Chat Completion automatically

### Requirement: Keyboard tools are defined and dispatched entirely in Lua
When Keyboard tools are enabled, package request policy SHALL expose exactly OpenAI function tools `type_text` and `press_enter`. `type_text` SHALL require exact string argument `text` and call `talkcan.keyboard_output.send_text` with the configured logical profile. `press_enter` SHALL require no text argument and call `send_key` with key `enter`. Each tool call SHALL use a stable Job effect key derived from the provider call identity, validate arguments before effect, normalize delivered/rejected/failed/indeterminate outcomes into bounded model-visible tool results, and append exactly one paired result before the next completion. Unknown, duplicate, malformed, disabled, over-bound, or parallel calls SHALL not cause a keyboard effect.

#### Scenario: Model requests type_text
- **WHEN** a completion returns one valid enabled `type_text` call
- **THEN** Lua SHALL execute one semantic keyboard-output operation and commit one paired normalized tool result
- **AND** Kotlin SHALL receive no OpenAI tool schema or call object

#### Scenario: Keyboard delivery is ambiguous
- **WHEN** keyboard output cannot prove whether delivery occurred
- **THEN** the tool effect and Job SHALL become indeterminate or failed according to generic work policy
- **AND** neither Lua nor host work recovery SHALL replay the text

### Requirement: Final assistant text uses generic synthesis and delayed playback
For final assistant text, the worker SHALL call `talkcan.synthesis` and admit the resulting opaque audio to `talkcan.playback` under the Job owner. Successful playback-queue admission SHALL occur before `Job:complete`. The host delayed-playback subsystem SHALL retain selection-aware pending/heard data independently after the work store purges transcript and effect bodies. Lua SHALL not reacquire a PTT route, select an output device, redirect another channel's response, or mark a response heard itself.

#### Scenario: Response arrives while another channel is selected
- **WHEN** Lua synthesizes and schedules an accepted response while its instance is unselected
- **THEN** host playback SHALL retain it for that instance under existing selection policy
- **AND** work completion SHALL not redirect or discard it

#### Scenario: Synthesis or scheduling fails
- **WHEN** synthesis or bounded playback admission returns failure
- **THEN** Lua SHALL fail the Job with a normalized non-secret outcome
- **AND** it SHALL not report a successfully scheduled response

### Requirement: Failures and observability remain bounded and provider private
The package SHALL normalize profile, secret, HTTP, JSON, model, completion, tool, synthesis, playback, cancellation, quota, and shutdown failures into bounded application/work outcomes. Package and host logs SHALL not contain API keys, authorization headers, prompts, transcripts, assistant text, tool arguments/results, HTTP bodies, or complete provider exceptions. Failure in one instance SHALL not close sibling actors or alter their queues/profiles. Terminal work SHALL purge content and retain only generic tombstones.

#### Scenario: Provider returns malformed body
- **WHEN** the OpenAI-compatible endpoint returns an unusable Chat Completion
- **THEN** the affected Job SHALL fail with a bounded package-defined reason
- **AND** no raw body or credential SHALL enter host observability

### Requirement: Lua and built-in OpenAI Agents coexist without migration
This change SHALL keep `builtin:openai-agent`, legacy OpenAI profile management, Java SDK composition, configuration, catalogue definitions, tests, and runtime behavior operational. Installing the external package SHALL add an independent provider identity. The host SHALL not automatically install it, create or select an instance, copy legacy configuration or credentials, alias implementation IDs, or bind old catalogue definitions to it. Full built-in removal and Kotlin OpenAI-agnostic cleanup SHALL require a later change.

#### Scenario: Both providers are installed
- **WHEN** a user creates one built-in and one external Lua OpenAI Agent
- **THEN** each SHALL retain separate implementation identity, profiles, configuration, runtime, queue/conversation state, and status
- **AND** both SHALL remain independently selectable for device evaluation

#### Scenario: External package is removed
- **WHEN** the user removes the Lua package during evaluation
- **THEN** its instances SHALL become unavailable through ordinary missing-provider behavior
- **AND** the built-in and its legacy profiles SHALL remain operational

