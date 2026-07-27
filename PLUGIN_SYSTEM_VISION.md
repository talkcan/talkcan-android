# Talkcan Plugin System Vision

## Status

This document is aspirational and non-normative. It describes the intended shape of the
Talkcan channel plugin ecosystem: its principles, boundaries, distribution model, runtime
lifecycle, common user surfaces, and major unresolved questions.

It is not:

- an implementation plan;
- a final Lua ABI or API specification;
- a package-format specification;
- a compatibility promise for particular Lua libraries;
- a release or migration checklist; or
- a commitment that every described surface ships in the first implementation.

Future OpenSpec changes and versioned interface specifications will turn selected parts of this
vision into concrete requirements.

### Pre-release v1 development policy

The Lua runtime, Talkcan API, package format, and plugin contracts remain one evolving
pre-release v1 until the complete v1 vision has been implemented and every hard-coded Kotlin
channel has been migrated to an external Lua plugin with equivalent behavior.

During this pre-release phase:

- `v1` identifies the target first production release, not a frozen compatibility promise;
- intermediate implementations and published test packages are development proofs rather than
  supported production releases;
- archived OpenSpec changes record the contracts implemented and verified at that point in
  development, but do not freeze those contracts or create compatibility obligations;
- later changes may revise any incomplete v1 callback, module, package, configuration,
  capability, lifecycle, or data contract directly;
- every affected in-tree caller, external development plugin, package fixture, and published test
  artifact is updated through a clean cutover;
- the host does not retain compatibility shims, parallel API generations, migration paths,
  version ranges, aliases, or deprecated intermediate behavior solely to support an earlier
  pre-release v1 state; and
- API v2 design and production compatibility policy begin only after the complete v1 feature set
  has shipped.

Feature parity includes migration of the current Journal, Debug, Keyboard, and OpenAI Agent
implementations from hard-coded Kotlin providers to external Lua packages using the same public
plugin system available to other publishers. Until that condition is met, an archived
specification means "implemented and verified at that development stage," not "permanently
versioned."

## Motivation

The current Kotlin channels—Journal, Debug, Keyboard, and OpenAI Agent—were built to discover
the application services, lifecycle rules, configuration needs, and interaction patterns that a
useful channel framework requires. They are working product implementations, but they are not
intended to remain permanent built-in channel implementations.

The long-term model is:

- the base application retains Android- and product-native facilities;
- channels are implemented as external Lua plugins;
- official channels move to their own repositories and use the same public plugin system as
  community channels; and
- channel authors can create behavior that the base application did not anticipate without
  requiring changes to the Talkcan application for each new integration.

The plugin system is inspired more by Neovim's trusted, programmable Lua ecosystem than by a
browser-extension permission model or a centrally reviewed marketplace.

## Core Vision

A Talkcan channel is an independently configured, event-driven Lua program running inside a
documented Talkcan Lua runtime.

A channel may react to user input, but user speech is not its defining characteristic. An enabled
channel may also poll a backend, maintain a subscription, process timers, receive host lifecycle
events, expose controls, and publish messages without a preceding PTT interaction.

Conceptually:

```text
Channel instance
    = Lua program
    + host-rendered configuration
    + plugin-owned durable state
    + independent runtime lifecycle
    + optional user and background event sources
    + Talkcan-native APIs
    + durable channel output
```

Channel selection and channel execution are separate concerns:

```text
Selection
    Chooses the destination for new user-driven PTT input.

Execution
    Allows every enabled instance to perform independent work while the
    Talkcan foreground service is alive.
```

An unselected channel is not dormant merely because it is not the current PTT destination.

## Design Principles

### Trusted programs, not restricted scripts

Installed plugins are trusted programs. The Lua runtime is intended to support:

- networking;
- filesystem access available through the documented Lua runtime;
- timers and an event loop;
- coroutines;
- arbitrary Lua computation;
- plugin-owned durable files and cache files; and
- vendored pure-Lua modules.

Talkcan does not attempt to anticipate every backend, protocol, retry policy, state machine, or
workflow that a plugin author may need. A plugin may implement those concerns directly in Lua.

This trust model is deliberate. Installing a plugin means trusting its publisher and future
installed updates. Community warnings and official-publisher provenance communicate that trust
decision; they are not substitutes for a security sandbox.

### Stable public boundaries

General Lua power does not imply access to the application's private object graph. Plugins do not
receive:

- raw Java, Kotlin, or Android objects;
- Android `Context` or platform service instances;
- Compose state or UI objects;
- internal repositories, coordinators, or mutable application state;
- raw Bluetooth, Telecom, audio-route, or recorder objects;
- JNI access;
- plugin-supplied native modules; or
- private implementation classes as a compatibility surface.

These exclusions preserve a stable plugin boundary and prevent plugins from binding themselves to
internal application organization.

### Host APIs exist for host-native facilities

Talkcan exposes APIs for facilities that cannot be implemented portably or correctly by ordinary
Lua code, or that must remain coordinated with application policy. Expected areas include:

- channel lifecycle and input events;
- opaque captured-audio handles;
- speech-to-text;
- text-to-speech;
- host-routed and half-duplex audio playback;
- keyboard and text-output integration;
- OpenAI connection profiles and host-managed credentials;
- durable channel messages and pending/heard state;
- phone, RSM, and Android Auto projections;
- declarative configuration and controls;
- structured plugin logging; and
- Android foreground-service and process lifecycle notifications.

The internal Kotlin capability and lease architecture may continue to implement resource ownership,
revocation, and cleanup. Those mechanisms are host implementation details rather than the public
programming model presented to Lua authors.

### Common surfaces remain host-rendered

Plugins provide data and declarations. The host renders shared surfaces.

This keeps phone configuration, RSM interaction, Android Auto presentation, accessibility,
navigation, audio feedback, and visual language coherent across independently developed plugins.
Plugins do not provide Compose code or arbitrary Android UI.

### Instance independence

One provider may back multiple channel instances. Each instance has independent:

- host-generated instance identity;
- user configuration;
- runtime generation;
- Lua globals and observable runtime state;
- timers and asynchronous work;
- durable data and cache directories;
- channel message history and pending state; and
- lifecycle and failure state.

Two instances of the same provider may point at different backends, use different credentials, or
serve different user purposes without sharing mutable state accidentally.

The implementation may choose how Lua virtual machines are allocated, but observable shared globals
between instances are outside this vision.

## Package and Provider Model

The initial ecosystem uses a strict cardinality:

```text
one GitHub repository
    = one plugin package
    = one channel provider
```

One provider may create multiple configured channel instances in the application.

The durable provider identity is the GitHub repository identity. Human-readable `owner/repository`
coordinates are useful for links and presentation, but repository renames and transfers must not
silently create a new provider or allow another repository to take over an installed provider's
identity. The exact representation of durable GitHub identity remains a later contract decision.

A package contains its manifest, Lua entrypoint, package-local Lua modules, assets, and supporting
documentation. Plugins may vendor pure-Lua dependencies. Inter-plugin dependencies and
plugin-supplied native modules are not part of this vision's initial package model.

## Discovery Without a Central Marketplace

Talkcan follows a decentralized discovery model rather than operating a submission-based
marketplace.

A public GitHub repository opts into discovery by adding the repository topic:

```text
talkcan-channel
```

The official Talkcan presence periodically discovers candidate repositories through GitHub and
presents conforming plugins. Developers do not need prior coordination or approval from the
Talkcan maintainers to become discoverable.

The GitHub topic is a discovery signal, not proof that a repository is valid, safe, maintained, or
endorsed. The discovery surface may exclude repositories that are archived, malformed, not
installable, impersonating another provider, or known to be malicious. Moderation and removal are
operational safeguards, not a reviewed-marketplace admission process.

The discovery surface should describe itself as an index of independently published GitHub
repositories, not as a curated catalogue.

## Trust Tiers

Discovery presents two publisher tiers.

### Official

A plugin is Official when it is published from the Talkcan GitHub organization (`talkcan`).

Official identifies organizational provenance. It does not claim that the plugin is free of defects
or that every release received a formal security audit. Official channels are expected to live in
the `talkcan` repository family (for example `talkcan/talkcan-channels`) and use the same public
plugin system as community channels.

### Community — Unreviewed

Every discovered plugin outside the Talkcan GitHub organization is Community — Unreviewed.

The discovery surface and application both present a clear warning that:

- the repository nominated itself through the public GitHub topic;
- Talkcan has not reviewed or endorsed the plugin;
- the plugin is trusted Lua code;
- it may use networking and filesystem facilities exposed by the Lua runtime; and
- it may process information supplied through Talkcan APIs.

Warnings appear in the application even when installation begins from a copied or independently
generated link rather than the official discovery surface.

## Repository Links, QR Codes, and Installation

The discovery surface presents a QR code or Android App Link identifying the GitHub repository.
The general discovery link identifies the repository rather than a mutable notion of "latest."

The application then:

1. resolves the repository identity;
2. queries its published GitHub releases;
3. inspects static package metadata without executing Lua;
4. filters releases for compatibility with the installed Talkcan application and supported plugin
   interfaces;
5. presents compatible stable versions;
6. optionally presents prereleases when the user explicitly opts in;
7. downloads the exact selected release artifact; and
8. validates the package before activation.

Talkcan installs release or opted-in prerelease artifacts. It never installs or executes the
repository's mutable default branch.

A future version-specific link may identify an exact release, asset, and digest for reproducible
installation. The repository-level link remains the normal discovery experience because it lets the
application choose among versions compatible with the current application.

The exact package archive, release-asset naming, digest, signing, and provenance formats remain
future specification work.

## The Talkcan Lua Runtime

The runtime is itself part of plugin compatibility. "Lua" alone is not a sufficient contract because
the standard language does not define HTTP, JSON, an asynchronous event loop, or a package
installation model.

The eventual runtime specification must define at least:

- Lua language and bytecode versions;
- integer and floating-point behavior;
- available standard libraries;
- module loading and package-local search paths;
- the supported timer and event-loop environment;
- networking primitives or bundled general-purpose networking libraries;
- JSON support;
- filesystem paths and working-directory behavior;
- package, instance-data, cache, and temporary directories;
- coroutine integration;
- error propagation and logging;
- shutdown and cancellation behavior; and
- whether synchronous work may block a runtime callback.

The intended experience is rich enough for plugin authors to implement ordinary backend clients,
pollers, parsers, local databases, and state machines without requesting a new domain-specific host
API.

Package-supplied native modules are excluded from the initial vision. This preserves portability
across Android architectures and keeps native crashes and private host access outside the public
plugin contract. Plugins may vendor pure-Lua dependencies.

## Runtime and Background Lifecycle

Each enabled channel instance may run independently while the Talkcan foreground service is alive,
regardless of which channel is selected.

Within that lifetime a plugin may own:

- timers;
- polling loops implemented cooperatively through the supported runtime;
- network requests and subscriptions;
- retry and backoff policy;
- backend cursors and deduplication state;
- plugin-specific queues; and
- asynchronous Lua coroutines.

The host owns creation, replacement, shutdown, failure containment, and the bridge to Android-native
resources.

The initial lifecycle does not guarantee plugin execution after the foreground service or application
process stops. Guaranteed scheduled execution, process resurrection, boot-time activation, and
continuous service retention are deferred to a future lifecycle revision.

Plugins must therefore treat runtime start as recoverable and reconstruct their in-memory work from
configuration and plugin-owned durable state when appropriate.

The exact runtime callbacks, ordering rules, deadlines, concurrency guarantees, and cancellation
protocol belong to the future ABI specification.

## Event-Driven Channels

PTT is one possible input source rather than the definition of a channel.

Potential channel event sources include:

- PTT preparation and captured input;
- PTT cancellation or failure;
- SOS;
- phone or RSM control invocation;
- plugin-owned timers;
- network responses and subscriptions;
- runtime startup and shutdown;
- configuration replacement; and
- recovery of plugin-owned state.

A channel may be:

- reactive, primarily responding to PTT;
- proactive, primarily responding to timers or remote events; or
- hybrid.

Selection remains the routing decision for new user-driven PTT input. It does not gate background
execution by other enabled instances.

## Durable Output and Playback

A proactive or unselected channel may publish durable pending messages. Publication is independent
of the PTT session model.

The host retains ownership of:

- durable user-visible message records;
- pending and heard state;
- channel association and ordering;
- audio synthesis and playback routing;
- half-duplex admission;
- current-channel policy;
- interruption and skip behavior; and
- cleanup after playback failure or cancellation.

Initial behavior does not guarantee that an unselected channel may interrupt current activity with
unsolicited TTS. Such output may remain pending until host policy admits it. A future revision may
introduce explicit, user-controlled interruption policy for channels that need urgent announcements.

The plugin may express the content and requested delivery intent. The host makes the final playback
decision.

## Plugin-Owned State

Trusted plugins may maintain arbitrary durable state in their instance-specific data directory.
Examples include:

- polling cursors;
- ETags and last-event identifiers;
- retry and backoff checkpoints;
- local indexes or databases;
- plugin-domain history; and
- cached backend metadata.

This state is distinct from:

- host-managed user configuration;
- host-managed secure credentials;
- disposable cache files;
- package files; and
- host-managed durable channel messages.

The eventual runtime specification must define directory lifetimes, package-update behavior, data
retention after disablement or uninstall, backup expectations, and isolation between instances.

## Declarative Configuration

Every channel provider declares host-rendered configuration.

Configuration has two separate declarative descriptions.

### Data schema

The data schema describes the persisted value model:

- field types and object structure;
- required and optional values;
- defaults;
- ranges and formats;
- additional-field behavior; and
- configuration schema version.

### UI schema

The UI schema describes how the host should present that data:

- field order;
- labels and explanatory text;
- grouping and sections;
- preferred widgets;
- visibility and dependency hints;
- dynamic host-owned choices;
- sensitive or redacted presentation; and
- suitability for phone or other host surfaces.

Separating these schemas prevents visual presentation from becoming the persisted data contract.
It also permits future host surfaces to render the same configuration differently.

Providers retain responsibility for configuration defaults, semantic validation, and forward
migration. Migration and validation must not depend on Android UI objects or private host state.
The exact declarative schema language and the division between declarative validation and Lua
validation remain unresolved.

Credentials, models, keyboard profiles, directories, and other host-owned resources may require
stable host references rather than embedding platform objects or secrets directly in ordinary
configuration values. Their exact representation remains future contract work.

## Declarative RSM Custom Menu

The initial vision includes a provider-defined custom RSM menu for the active channel instance.
The host owns menu state, button routing, announcements, navigation, timeout behavior, and rendering.
The provider declares entries and receives configured actions; it does not implement its own RSM
button parser or audio-navigation system.

### Top-level menu navigation

The existing Channels menu remains a top-level Control-mode menu.

The intended default cycle is:

```text
Active mode
    │
    │ Group
    ▼
Control mode: Channels menu
    │
    │ Group
    ▼
Control mode: active channel's custom menu
    │
    │ Group
    ▼
Control mode: Channels menu
```

Additional host-defined top-level menus may be introduced later. Repeated Group presses move between
top-level menus; the default order places the active channel's custom menu immediately after the
Channels menu.

Only the active channel instance supplies the custom channel menu.

### Custom menu interaction

Inside the active channel's custom menu:

- Volume Up and Volume Down navigate through provider-defined entries.
- SOS may invoke a provider-defined action for the selected entry.
- PTT may invoke a provider-defined action for the selected entry.
- A PTT press in the custom menu always exits Control mode after invoking its optional action.
- That same PTT press does not begin normal voice capture.

The available physical interaction categories are therefore:

```text
Volume Up / Down
    Navigate entries.

SOS
    Invoke the selected entry's optional SOS action.

PTT
    Invoke the selected entry's optional PTT action, then force exit from Control mode.
```

A provider may use these actions for behavior such as changing a value among a set of choices,
toggling a feature, acknowledging an item, refreshing data, or invoking another channel-specific
operation.

The exact entry data model, action result model, value announcement behavior, empty-menu behavior,
error feedback, persistence semantics, and accessibility vocabulary remain future interface design.

## Reliability Boundary

Trusted code does not eliminate the need for application reliability.

The host should isolate plugin failures sufficiently that one defective channel does not collapse
unrelated channel instances or corrupt Android lifecycle ownership. The intended reliability
properties include:

- plugin callbacks do not execute on the Android main thread;
- one instance's exception does not terminate unrelated instances;
- channel input remains associated with the runtime generation that accepted it;
- closing or replacing a runtime prevents late Talkcan API effects;
- host-owned audio, Bluetooth, Telecom, UI, and native-resource cleanup remains authoritative;
- package or configuration failure leaves the channel definition visible but unavailable; and
- runtime shutdown is explicit and idempotent from the host's perspective.

CPU limits, memory limits, callback deadlines, event-loop behavior, and recovery after an
uncooperative plugin remain future runtime-policy decisions. Their purpose is service reliability,
not a claim that installed code is untrusted or least-privileged.

## Versioning Dimensions

The ecosystem will require several independent versions rather than one overloaded plugin version:

- package release version;
- package-manifest format version;
- Talkcan Lua runtime version;
- core channel API version;
- versions of individual Talkcan-native API modules;
- provider configuration schema version; and
- potentially RSM control-schema and presentation-schema versions.

The exact compatibility-range syntax, additive-change policy, deprecation process, and major-version
rules remain future ABI work. Package version alone must not be treated as the host API version.

## Explicit Non-Goals of This Vision

This document does not define:

- the Lua engine implementation;
- the exact networking or event-loop library;
- arbitrary native module loading;
- raw Android or Kotlin access;
- a central marketplace submission process;
- pre-install source review by Talkcan;
- guaranteed execution after the foreground service stops;
- immediate unsolicited TTS interruption by unselected channels;
- arbitrary plugin-rendered Android UI;
- cross-plugin dependencies;
- a final package-signing scheme;
- a final configuration schema language;
- a final RSM control ABI; or
- the migration sequence from current Kotlin channels.

## Unresolved Questions

The following decisions intentionally remain open for later proposals and specifications:

- Which Lua language version and engine define the runtime?
- Which general-purpose event-loop, networking, JSON, and filesystem modules are guaranteed?
- Are package files immutable at runtime, and what paths are visible to Lua?
- How are repository identity, transfers, deletion, and ownership changes represented durably?
- What release asset, digest, signature, or attestation formats are required?
- How does the app inspect compatibility metadata efficiently across GitHub releases?
- What are the exact runtime startup, shutdown, callback, and cancellation events?
- How does a plugin request or observe foreground-service retention in a future lifecycle revision?
- What is the declarative data-schema language?
- What is the declarative UI-schema language?
- Which host-owned resources are represented by stable references in configuration?
- How are secure credentials selected and consumed from Lua?
- What exact Talkcan API modules ship initially?
- How are API compatibility ranges and additive changes expressed?
- What is the RSM custom-menu entry and action-result model?
- How will future user-controlled unsolicited TTS interruption be represented?
- How are plugin data retained, exported, reset, or removed across uninstall and reinstall?

These questions are boundaries for later design work, not omissions to be silently decided by the
first implementation.
