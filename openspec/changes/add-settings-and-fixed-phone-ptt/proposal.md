## Why

Talkcan currently has no stable settings system, so system configuration is scattered across dashboard tiles, channel management mode, and developer-facing routes. Channel cards also combine destination selection with phone PTT, causing the PTT target to move with scrolling and catalogue order instead of occupying a fixed, memorable location.

## What Changes

- Add a first-class Settings destination with a stable hierarchy separating app-wide device, audio, speech, integration, storage, and advanced settings from per-channel configuration.
- Keep preferred audio-device selection on the operational dashboard while moving device setup and administration into Settings.
- Clarify that the selected audio device controls newly admitted incoming playback and that pressing any device's PTT automatically selects that device's home mode.
- Replace channel-card long-press and slide-to-lock PTT with one fixed-position phone PTT control targeting the active channel.
- Make channel cards selection-only on their primary surface while retaining explicit pending-message and channel-configuration affordances.
- Keep the fixed phone PTT control at a stable screen coordinate across scrolling, channel selection, readiness, playback, and capture states; update its content and enabled state without moving it.
- Remove hidden long-press setup gestures from audio-device tiles in favor of explicit Settings navigation.
- **BREAKING** Remove channel-card PTT and its inward slide-to-lock interaction.

## Capabilities

### New Capabilities
- `settings-navigation`: First-class Settings navigation, settings hierarchy, and ownership boundaries between app-wide and channel-specific configuration.
- `fixed-phone-ptt`: Persistent phone PTT control, active-channel targeting, phone-mode auto-switch, stable placement, and capture-state feedback.

### Modified Capabilities
- `main-device-dashboard`: Channel cards become activation selectors rather than PTT surfaces; the dashboard gains a fixed phone PTT region and explicit Settings access.
- `input-mode`: The dashboard selector is presented as preferred audio-device selection for future playback, while every admitted PTT actuator continues to auto-select its own home mode.
- `phone-channel-card-ptt`: Remove channel-card long-press, inward slide-lock, and card-local recording controls in favor of the new fixed phone PTT capability.

## Impact

- Affects the Compose dashboard, root navigation, channel-card interaction modifiers, phone PTT gesture/state handling, input-mode presentation, and existing device/configuration screens.
- Reuses the existing `InputModeController.autoTransitionFor(PttSource)` and mode-specific playback routing; no audio-routing or channel-dispatch protocol change is intended.
- Existing package, profile, voice, RSM, car, storage, and diagnostics screens become destinations within the Settings hierarchy rather than controls hidden inside channel management.
- Existing channel catalogue ordering, active-channel identity, pending-response projection, provider-owned configuration schemas, and hardware PTT behavior remain unchanged.
- Tests covering channel-card PTT, mode-tile long presses, dashboard navigation, and phone PTT gestures require replacement or migration to the new contracts.
