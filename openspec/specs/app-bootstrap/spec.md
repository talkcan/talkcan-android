# App Bootstrap

## Purpose

Define the app bootstrap lifecycle, passive loading, readiness gates, visual identity, and recovery.

## Requirements

### Requirement: Launch starts with passive bootstrap loading
The system SHALL render a passive loading surface from cold activity creation until prerequisite checking determines the next valid root state. The system SHALL NOT represent unknown permission, model, service, voice, or core-initialization state as an actionable setup state.

#### Scenario: Returning user cold launch
- **WHEN** the app is cold-launched with all setup prerequisites already satisfied
- **THEN** the loading surface is shown while prerequisites and core readiness are checked
- **AND** the setup screen is never shown during that successful launch

#### Scenario: Service binding is pending
- **WHEN** the activity is visible but the bootstrap-owning service is not yet bound
- **THEN** the loading surface shows a passive service-starting stage
- **AND** the dashboard is not rendered from a default placeholder application state

#### Scenario: Missing prerequisite discovered
- **WHEN** prerequisite checking identifies a missing permission, invalid model set, or missing offline navigation voice with a known user remedy
- **THEN** the system replaces loading with the actionable setup surface

#### Scenario: Missing offline navigation voice discovered
- **WHEN** prerequisite checking identifies an absent Android TTS engine, a missing or incomplete offline English voice, a failed voice selection, a failed synthesis probe, or a probe timeout
- **THEN** the system replaces loading with the actionable setup surface exposing the Android TTS settings or voice install action
- **AND** the system classifies the prerequisite as `NeedsSetup`

### Requirement: Bootstrap state is authoritative
The system SHALL expose one lifecycle-aware bootstrap state as the source of truth for loading, setup, recovery, and dashboard eligibility. Activity recreation or rebinding SHALL reconstruct the root surface from that state rather than defaulting to setup or dashboard.

#### Scenario: Activity is recreated during checking
- **WHEN** the activity is recreated while prerequisite checking, including offline navigation voice probing, is in progress
- **THEN** the recreated activity shows loading for the current observed stage
- **AND** it does not show setup unless the coordinator has identified a missing user action

#### Scenario: Activity is recreated during core preparation
- **WHEN** the activity is recreated while model acquisition, native STT initialization, Supertonic initialization, or controller construction is in progress
- **THEN** the recreated activity shows the current loading stage and available real progress
- **AND** it does not start a duplicate bootstrap operation

### Requirement: Core readiness gates dashboard entry
The system SHALL enter the dashboard only after all required core readiness conditions have completed successfully: runtime permissions are granted, all required model assets pass full verification, a proven installed offline English navigation voice has been successfully selected and probed during prerequisite checking and retained through core preparation, native STT reports `Ready`, Supertonic reports `Ready`, and required model-backed controllers are constructed. Supertonic readiness SHALL remain a hard bootstrap gate. A proven offline English navigation voice SHALL be a hard bootstrap gate: a missing engine, missing or incomplete offline voice, failed voice selection, failed synthesis probe, or probe timeout SHALL transition bootstrap to `NeedsSetup` and SHALL NOT enter the dashboard. The retained Android TTS renderer SHALL never perform network synthesis or engine-owned audible playback. The system SHALL NOT require any system-announcement phrase to be pre-rendered or cached for dashboard entry.

#### Scenario: Disk assets valid but native engine loading
- **WHEN** model files pass integrity verification but either native speech engine has not reported `Ready`
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Offline navigation voice not proven
- **WHEN** prerequisite checking identifies a missing or unusable offline English navigation voice
- **THEN** the system transitions to `NeedsSetup` with the Android TTS settings or voice install action exposed
- **AND** the dashboard is not shown
- **AND** native STT and Supertonic initialization do not proceed

#### Scenario: Engines ready but controllers pending
- **WHEN** STT and TTS engines are ready and the offline navigation voice is proven but any required controller has not been successfully constructed
- **THEN** the loading surface remains visible
- **AND** the dashboard is not shown

#### Scenario: Core readiness complete
- **WHEN** every core readiness condition completes successfully including the proven offline navigation voice from prerequisite checking
- **THEN** the system automatically replaces loading with the dashboard
- **AND** it does not wait for an acknowledgement or for a loading animation to finish

### Requirement: Optional external readiness does not block bootstrap
The system SHALL NOT require RSM bonding, SPP connection, HFP availability, Keyboard BLE connection, Android Auto presence, Telecom routing, serial reconnection, or journal recovery to complete global bootstrap.

#### Scenario: RSM is unavailable at core readiness
- **WHEN** core readiness completes while the RSM is powered off, unpaired, disconnected, or missing an audio profile
- **THEN** the dashboard is shown
- **AND** the RSM tile reflects its existing unavailable or setup state

#### Scenario: Optional integrations are absent
- **WHEN** core readiness completes without Keyboard BLE or Android Auto connectivity
- **THEN** the dashboard is shown
- **AND** each optional capability continues to report its own readiness independently

#### Scenario: Journal recovery continues
- **WHEN** core readiness completes while journal recovery work remains in progress
- **THEN** the dashboard is shown
- **AND** journal recovery continues outside the global bootstrap gate

### Requirement: Loading reports observed work
The loading surface SHALL identify the current observed bootstrap stage and SHALL display measurable progress only when the underlying operation reports a real count or byte total. The system SHALL NOT manufacture a combined percentage, random log output, or fictional initialization activity.

#### Scenario: Model file download reports bytes
- **WHEN** a model file download reports bytes read and a positive total byte count
- **THEN** loading displays the current file or model set and corresponding measurable progress

#### Scenario: Stage has no measurable total
- **WHEN** a bootstrap stage reports no meaningful total
- **THEN** loading identifies the stage without presenting a fabricated percentage

### Requirement: Loading follows the Talkcan visual identity
The loading surface SHALL use the existing Night Ops and Daylight Material color schemes, Chakra Petch and Inter typography, and a restrained animated Analog-to-Routed Wave motif representing voice becoming routed digital signal. Ordinary loading SHALL use the theme primary accent and SHALL reserve warning/error treatment for actual failures.

#### Scenario: Night Ops loading
- **WHEN** the app uses the dark theme
- **THEN** loading uses the Void/Hull surfaces, Talkcan Cyan for active scanning/readiness, and existing night text colors
- **AND** it does not use Alert Amber as ordinary progress color

#### Scenario: Daylight loading
- **WHEN** the app uses the light theme
- **THEN** loading uses Hull White/Deck Plating surfaces, Command Gold for primary progress, and existing daylight text colors

#### Scenario: Reduced or disabled animation
- **WHEN** platform animation is reduced or disabled
- **THEN** loading remains understandable through a static routed-wave treatment, stage labels, and state indicators
- **AND** no bootstrap behavior depends on animation completion

#### Scenario: Loading animation content
- **WHEN** the loading surface is visible
- **THEN** it renders at most one restrained routed-signal animation plus event-driven status and stage indicators
- **AND** it does not render starfields, spacecraft, CRT flicker, fictional telemetry, or scrolling fake logs

### Requirement: Bootstrap failures terminate in recovery
The system SHALL convert prerequisite inspection errors, native engine failures, controller construction failures, and finite-stage timeouts into an explicit recovery state containing the failed stage, a concrete diagnostic, and a retry action when retry is safe. It SHALL NOT leave the user on an infinite loading indicator. A missing or unusable offline navigation voice SHALL NOT be classified as a recovery failure; it SHALL be classified as `NeedsSetup` with the Android TTS settings or voice install action exposed. Retry SHALL cancel and join the prior structured bootstrap attempt before discarding its controllers or starting replacement prerequisite/core work.

#### Scenario: Native model reports failure
- **WHEN** either native speech engine reports `Failed`
- **THEN** loading changes to recovery for that engine
- **AND** the dashboard is not shown
- **AND** a diagnostic and retry action are presented

#### Scenario: Finite stage times out
- **WHEN** a finite core-initialization stage exceeds its configured timeout
- **THEN** loading changes to recovery with the timed-out stage identified
- **AND** retry does not leave jobs or controllers from the previous attempt active

#### Scenario: Offline voice probe timeout is classified as setup
- **WHEN** the offline English navigation voice probe exceeds its configured timeout
- **THEN** the system does not enter recovery
- **AND** the system classifies the prerequisite as `NeedsSetup` with the Android TTS settings or voice install action exposed

#### Scenario: Retry succeeds
- **WHEN** the user retries a recoverable bootstrap failure and all core conditions subsequently complete
- **THEN** the system automatically enters the dashboard

#### Scenario: Retry waits for the prior attempt to finish cancellation
- **WHEN** a prior bootstrap attempt is in a blocking operation that ignores cancellation until it returns and the user requests retry
- **THEN** the retry job calls `cancelAndJoin` on the prior attempt before discarding controllers
- **AND** the replacement attempt starts only after the prior attempt has completed cancellation and cleanup
- **AND** the loading state does not report replacement progress before that handoff is complete

#### Scenario: Concurrent retries share one replacement attempt
- **WHEN** multiple retry actions occur while the first retry job is joining the prior attempt
- **THEN** subsequent retry actions do not create another replacement attempt
- **AND** controller discard occurs once for the tracked replacement
- **AND** a cancellation clears both tracked attempt and retry jobs

### Requirement: Runtime TTS renderer hard-gate loss is bounded
After bootstrap completes, a runtime Android TTS renderer failure SHALL trigger at most one bounded reinitialization, offline-voice probe, and retry of only the newest pending navigation announcement. Every outcome of the bounded recovery SHALL be exhaustive and SHALL NOT leave the system in a degraded `Ready` state with navigation unavailable. If the bounded recovery probe and retry both succeed, the system SHALL remain `Ready` and resume navigation synthesis. If the recovery probe reveals a missing or unusable Android TTS engine or offline English voice, the bootstrap state SHALL transition to `NeedsSetup` with the Android TTS settings or voice install action exposed. If the recovery probe succeeds but the newest pending announcement retry fails, or if the renderer's file access, decoder, or PCM normalization infrastructure fails, the bootstrap state SHALL transition out of `Ready` to a retryable `BootstrapState.Failed` with a concrete diagnostic and SHALL drop the failed generation entirely. The budget of one bounded recovery cycle SHALL apply per independent failure chain: a recovery retry failure within the same chain SHALL NOT trigger a second cycle, and a later independent announcement failure after a fully successful recovery SHALL be eligible for one new bounded recovery cycle.

#### Scenario: Bounded recovery succeeds
- **WHEN** a runtime TTS renderer failure triggers one bounded reinitialization and voice probe
- **AND** the probe succeeds and the newest pending announcement retry is synthesized
- **THEN** the system remains `Ready` and navigation synthesis resumes

#### Scenario: Recovery reveals missing or unusable voice
- **WHEN** the bounded recovery probe reveals a missing or unusable Android TTS engine or offline English voice
- **THEN** the bootstrap state transitions to `NeedsSetup`
- **AND** the Android TTS settings or voice install action is exposed

#### Scenario: Recovery probe succeeds but announcement retry fails
- **WHEN** the bounded recovery probe succeeds and a proven offline English voice is available
- **AND** the retry of the newest pending announcement fails to produce valid normalized PCM
- **THEN** the bootstrap state transitions out of `Ready` to a retryable `BootstrapState.Failed` with a concrete diagnostic
- **AND** the failed generation is dropped entirely

#### Scenario: Renderer infrastructure fails during recovery
- **WHEN** the bounded recovery fails due to a renderer file access, decoder, or PCM normalization infrastructure error
- **THEN** the bootstrap state transitions out of `Ready` to a retryable `BootstrapState.Failed` with a concrete diagnostic
- **AND** the failed generation is dropped entirely

#### Scenario: One bounded recovery per independent failure chain
- **WHEN** a runtime TTS renderer failure triggers a bounded reinitialization, voice probe, and pending-announcement retry
- **THEN** the system SHALL NOT attempt a second reinitialization, probe, or retry within that same failure chain
- **AND** a recovery retry failure within the same chain SHALL transition to `BootstrapState.Failed` without triggering another recovery cycle
- **AND** after a fully successful recovery and retry, a later independent announcement failure SHALL be eligible for one new bounded recovery cycle
