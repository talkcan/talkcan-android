## Purpose

Defines the host-owned phone dashboard user interface, including channel card rendering, configuration form generation, profile management, and projection of asynchronous channel execution and responses.

## Requirements

### Requirement: Dashboard shows channels
The system SHALL render one host-owned functional card per channel instance in the authoritative catalogue order, including instances whose implementation provider is absent, incompatible, or failed to load. Each card SHALL retain the instance's stable ID and display name and SHALL present its readiness, execution status, active state, and provider-defined presentation metadata when that metadata is available. The host SHALL interpret descriptor metadata into native Android UI and SHALL NOT allow a provider, configuration payload, or script to supply or control Android views, composables, navigation, or other platform UI objects. Functional cards for available instances SHALL remain mutually exclusive activation zones and phone-side PTT zones. Dedicated configuration and catalogue-management controls SHALL remain outside the phone PTT gesture surface.

The channel panel SHALL discover creatable implementations and their labels, summaries, default configuration, configuration schema, and presentation metadata from registered provider descriptors rather than from a closed built-in-kind list. The host SHALL render native configuration forms from descriptor schemas, validate submitted values against the provider-owned schema before committing them, and address creation and configuration by implementation ID and instance ID. The panel SHALL provide access to add supported provider-backed instances, rename instances, reorder instances, and remove instances subject to catalogue invariants. An unavailable instance SHALL remain visible at its catalogue position with a host-rendered reason and an actionable recovery, reconfiguration, or removal affordance; it SHALL NOT become a script-controlled UI surface or be silently dropped. The dashboard SHALL NOT render nonfunctional mock channel cards as catalogue entries.

#### Scenario: Ordered catalogue renders
- **WHEN** the dashboard receives an ordered runtime snapshot containing available and unavailable channel instances
- **THEN** it SHALL render exactly one host-owned card per catalogue instance
- **AND** each card SHALL retain the instance's stable ID
- **AND** card order SHALL match the catalogue order

#### Scenario: Descriptor metadata drives available presentation
- **WHEN** a catalogue instance resolves to a registered provider descriptor
- **THEN** the dashboard SHALL derive the card's provider label, summary, and presentation metadata from that descriptor
- **AND** the Android host SHALL render those values through native dashboard components
- **AND** the provider SHALL NOT supply an Android view, composable, navigation destination, or executable UI callback

#### Scenario: Channel selected for activation
- **WHEN** the user taps the main surface area of any persisted functional channel card
- **THEN** the system SHALL set that card's stable instance ID as the single active channel regardless of preparation state

#### Scenario: Ready channel long-pressed for PTT
- **WHEN** the user long-presses the main surface area of a ready functional channel card
- **THEN** the system SHALL set that card's instance ID as active
- **AND** start a phone-originated PTT session for that instance
- **AND** show held-recording feedback on that card

#### Scenario: Held phone PTT shows lock direction
- **WHEN** a phone-originated PTT session is active from a functional channel card and is not locked
- **THEN** the dashboard SHALL show a lock instruction on that card
- **AND** the instruction SHALL point inward from the initial press side

#### Scenario: Locked phone PTT shows stop affordance
- **WHEN** a phone-originated PTT session from a functional channel card has been slide-locked
- **THEN** the dashboard SHALL show that the PTT session is locked on that card
- **AND** it SHALL show an explicit stop affordance

#### Scenario: Unready instance remains selectable
- **WHEN** a catalogue instance lacks valid configuration or a required live dependency
- **THEN** its card SHALL remain visible and selectable at its catalogue position with its stable ID and display name
- **AND** it SHALL display its not-ready state and host-rendered configuration or recovery access
- **AND** long-pressing its main surface SHALL set it active and attempt phone PTT admission
- **AND** non-recoverable unavailability SHALL produce host-owned problem feedback without capture
- **AND** a selected unready card SHALL use the same active color and `ACTIVE` status language as a selected ready card
- **AND** an unselected unready card SHALL use the same standby color language as other unselected cards while retaining its unavailable diagnostic

#### Scenario: Provider is absent or incompatible
- **WHEN** a catalogue instance references an implementation provider that is absent or incompatible
- **THEN** its card SHALL remain visible and selectable at its catalogue position with its stable ID and display name
- **AND** the card SHALL identify the implementation as unavailable without exposing opaque configuration contents
- **AND** the card SHALL provide host-owned recovery or removal actions
- **AND** PTT SHALL produce host-owned problem feedback rather than executing provider code or starting capture

#### Scenario: Provider fails to load
- **WHEN** a registered provider fails while its descriptor or runtime is loaded
- **THEN** the affected instance card SHALL remain visible at its catalogue position
- **AND** the host SHALL present an actionable unavailable state without rendering provider-supplied UI
- **AND** unaffected instance cards SHALL remain usable

#### Scenario: Channel configured but inactive
- **WHEN** a ready instance is not the active channel
- **THEN** its card SHALL display a visually inactive Ready or Standby state

#### Scenario: Channel card opens descriptor-driven configuration
- **WHEN** the user activates the dedicated configuration control on an instance whose provider descriptor is available
- **THEN** the system SHALL open a host-rendered native configuration form for that instance ID using the descriptor's configuration schema
- **AND** it SHALL preserve the active selection
- **AND** it SHALL NOT start, lock, stop, or release phone PTT
- **AND** it SHALL NOT execute provider-controlled Android UI

#### Scenario: Invalid configuration submission
- **WHEN** the user submits values that fail the provider descriptor's configuration schema
- **THEN** the host SHALL reject the submission without committing catalogue changes
- **AND** the native form SHALL identify the invalid fields or schema-level error

#### Scenario: Add channel instance
- **WHEN** the user chooses a registered creatable provider descriptor and submits valid initial configuration through the host-rendered form
- **THEN** the dashboard SHALL request creation of a new catalogue instance using that descriptor's stable implementation ID and validated configuration
- **AND** the new card SHALL appear at the resulting catalogue position

#### Scenario: Every registered creatable provider remains reachable
- **WHEN** the catalogue-management form is rendered at the minimum supported phone width
- **THEN** the creation control for every registered creatable production provider descriptor SHALL remain visible and actionable
- **AND** the presence or absence of an existing instance for that implementation SHALL NOT hide or disable creation

#### Scenario: Reorder channel instance
- **WHEN** the user moves an instance through the catalogue-management surface
- **THEN** the dashboard SHALL render the committed order
- **AND** the active instance SHALL remain active
- **AND** availability SHALL NOT change the instance's stable ID or catalogue position

#### Scenario: Remove channel instance
- **WHEN** the user confirms removal of a removable instance
- **THEN** the dashboard SHALL remove its card after the catalogue mutation commits
- **AND** it SHALL render any active-selection repair from the same committed snapshot

#### Scenario: Final channel cannot be removed
- **WHEN** the catalogue contains one instance
- **THEN** the dashboard SHALL prevent or reject removal of that final instance

#### Scenario: No mock catalogue entries
- **WHEN** the dashboard renders the channel panel
- **THEN** every channel card SHALL correspond to a persisted catalogue instance
- **AND** the dashboard SHALL NOT render the Command Uplink preview as a channel card

### Requirement: Dashboard title long-press shortcut
The dashboard SHALL support a developer shortcut where long-pressing the application title in the header navigates the user/developer directly to the Log Analysis view.

#### Scenario: Long-pressing title navigates to logs
- **WHEN** the user long-presses the application title ("TALKCAN") in the dashboard header
- **THEN** the system SHALL navigate to the Log Analysis screen


### Requirement: Dashboard projects asynchronous channel execution and responses
The dashboard SHALL render each channel's host-owned asynchronous projection alongside its existing catalogue identity, readiness, active state, and execution status. The projection SHALL expose queued user-turn count, processing state, pending unheard response count, and playback-pending state when a response is waiting for channel selection or audio admission. The dashboard SHALL update these values from host projections without importing provider SDK types, Android media objects, audio-route objects, or transport objects.

#### Scenario: Channel turn is processing
- **WHEN** a channel has a released user turn in queued, transcribing, running, waiting-for-tool, or synthesizing work
- **THEN** its dashboard card SHALL present a host-owned processing state
- **AND** the card SHALL expose the queued user-turn count when one or more turns are waiting
- **AND** the card SHALL remain selectable as the active channel
- **AND** the card SHALL NOT expose provider or SDK implementation details

#### Scenario: Channel has pending responses
- **WHEN** a channel projection reports one or more pending unheard assistant responses
- **THEN** the dashboard card SHALL present the pending response indication and count
- **AND** the count SHALL remain associated with that channel's stable instance ID
- **AND** the card SHALL remain visible when another channel is active

#### Scenario: Pending response is heard
- **WHEN** pending response playback completes or the user explicitly skips the response
- **THEN** the dashboard SHALL refresh the channel projection
- **AND** the card SHALL reduce or remove the pending unheard indication according to the updated count

#### Scenario: Processing or playback projection changes
- **WHEN** a channel transitions between idle, processing, pending, playback, completed, or error state
- **THEN** the dashboard SHALL render the latest host-owned projection without changing catalogue order or stable identity

### Requirement: Dashboard manages global OpenAI-compatible connection profiles
The dashboard SHALL provide host-rendered management for global OpenAI-compatible connection profiles independently of channel instances. Each profile SHALL have a stable profile ID, display name, base URL, host-owned bearer credential, model-discovery availability, and typed availability or error state. A global profile SHALL NOT contain a channel-specific model selection. The dashboard SHALL never render a stored credential in clear text, SHALL NOT expose credentials to channel implementations, and SHALL NOT require per-call authorization controls.

#### Scenario: Create a connection profile
- **WHEN** the user submits a valid display name, base URL, and bearer credential in the global profile form
- **THEN** the host SHALL persist a new profile with a stable profile ID
- **AND** the profile SHALL appear in the global profile list with its host-owned availability state
- **AND** the dashboard SHALL not display the stored credential value after submission

#### Scenario: Edit a connection profile
- **WHEN** the user edits a persisted profile's display name, base URL, or credential and submits valid values
- **THEN** the host SHALL update that profile by stable profile ID
- **AND** channel configurations referring to that profile SHALL continue to refer to the same profile ID
- **AND** the dashboard SHALL refresh the profile's discovery and availability state

#### Scenario: Model discovery is unavailable
- **WHEN** a profile's models endpoint cannot be reached or returns an error
- **THEN** the dashboard SHALL retain the profile in the global list
- **AND** it SHALL show a typed unavailable or error state and recovery action
- **AND** it SHALL NOT fabricate model choices

#### Scenario: Remove a profile referenced by a channel
- **WHEN** the user removes a global profile that is referenced by one or more channel instances
- **THEN** the host SHALL preserve each channel instance and its stable configuration
- **AND** each affected channel SHALL report a missing-profile unavailable state
- **AND** no affected channel card SHALL be silently removed from the dashboard

### Requirement: Dashboard configures an OpenAI Agent channel from host-owned profile and model choices
The dashboard SHALL provide a host-rendered native configuration form for an OpenAI Agent channel instance. The form SHALL select one global connection profile by stable profile ID, select one model ID discovered from that selected profile's models endpoint, accept a multiline system prompt, and optionally enable configured Keyboard tools with a selected host keyboard profile or layout. The channel SHALL store the model selection by channel instance rather than in the global profile. Tool calls enabled by this configuration SHALL execute automatically without a per-call authorization prompt.

#### Scenario: Configure an agent channel with discovered model
- **WHEN** the user opens an OpenAI Agent channel configuration form
- **AND** at least one global profile has available discovered models
- **THEN** the form SHALL offer the available profiles and models as host-owned choices
- **AND** submitting valid values SHALL persist the selected profile ID, model ID, multiline system prompt, and Keyboard-tool settings on that channel instance
- **AND** the form SHALL NOT request or display a channel-specific credential

#### Scenario: Model choices are scoped to the selected profile
- **WHEN** the user changes the selected global profile
- **THEN** the form SHALL refresh model choices from that profile's discovery result
- **AND** it SHALL NOT offer models discovered only from another profile as if they were available
- **AND** an unavailable prior model selection SHALL be reported as invalid or unavailable until replaced

#### Scenario: Keyboard tools are enabled
- **WHEN** the user enables Keyboard tools for an agent channel
- **THEN** the form SHALL require a selected host keyboard profile or layout
- **AND** the enabled tool set SHALL include `type_text` and `press_enter` actions
- **AND** the dashboard SHALL persist only the host-owned keyboard selection and tool configuration, not Android input objects

#### Scenario: Profile or model is unavailable during configuration
- **WHEN** the selected profile is missing, unavailable, or has no discoverable selected model
- **THEN** the form SHALL preserve the channel instance and its non-secret configuration
- **AND** it SHALL identify the missing profile or model with a host-owned recovery path
- **AND** it SHALL prevent the channel from being presented as ready solely because an old model ID remains stored

### Requirement: Dashboard keeps agent channels visible when their profile is missing
The dashboard SHALL keep an OpenAI Agent channel card visible at its persisted catalogue position when its configured global profile is absent, deleted, incompatible, or unavailable. Such a card SHALL retain its stable instance ID and display name, SHALL show a host-owned unavailable reason, and SHALL offer profile management or reconfiguration access. Selecting the card SHALL still use the shared active-channel path, while a PTT attempt SHALL follow host-owned unavailable feedback without starting capture.

#### Scenario: Configured profile is missing
- **WHEN** an agent channel references a profile ID that is not present in global profile storage
- **THEN** the dashboard SHALL render the channel card in an unavailable state at its existing position
- **AND** the card SHALL identify that the connection profile is missing
- **AND** the card SHALL offer an action to add or select a profile

#### Scenario: Missing-profile card is selected
- **WHEN** the user taps a missing-profile agent channel card
- **THEN** the system SHALL set that channel's stable instance ID as active
- **AND** the card SHALL retain its unavailable diagnostic
- **AND** the dashboard SHALL NOT start capture or execute provider-controlled UI

#### Scenario: Profile becomes available again
- **WHEN** a profile with the configured stable profile ID is restored or becomes available
- **THEN** the host SHALL re-evaluate the affected channel's profile and model readiness
- **AND** the dashboard SHALL refresh the card without replacing its instance ID or silently changing its model selection
