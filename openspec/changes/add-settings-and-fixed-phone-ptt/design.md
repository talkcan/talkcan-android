## Context

Talkcan is hardware-first: RSM, car, and phone PTT actuators each auto-transition `InputMode` to their home mode before route resolution, while the selected mode also determines the output endpoint for later admitted channel-content playback. The dashboard currently labels this state as `Talk from`, hides device setup behind tile gestures, hides package/profile administration behind a channel-management toggle, and exposes diagnostics as `Activity`.

Phone PTT is currently attached to each channel card. Tap selects the channel; long-press selects and starts PTT; horizontal movement can lock recording; card-local content exposes recording and stop state. This makes the PTT target move with catalogue order, scrolling, dynamic card height, and channel selection.

The change must preserve existing channel identity, provider-generated configuration, actuator auto-transition, route ownership, half-duplex admission, and hardware PTT behavior. It must remove the card/PTT coupling without adding a second routing model.

## Goals / Non-Goals

**Goals:**

- Give configuration a stable, first-class Settings destination and hierarchy.
- Separate app-wide settings from per-channel configuration.
- Preserve preferred audio-device selection as an operational dashboard control for incoming playback.
- Explain that pressing any PTT automatically selects that actuator's audio device.
- Provide one fixed-position phone PTT control that always targets the active channel.
- Make channel-card primary surfaces selection-only.
- Remove hidden setup gestures and obsolete card-local PTT state.
- Preserve stable placement, clear capture ownership, and accessible state feedback across all PTT phases.

**Non-Goals:**

- Adding channel conversation history or a History destination.
- Changing channel dispatch, message persistence, pending-response semantics, or priority-channel behavior.
- Changing RSM or Android Auto actuator mappings, audio-route acquisition, or half-duplex policy.
- Redesigning provider configuration schemas or allowing providers to render Android UI.
- Adding new package, profile, speech-model, storage, or device-management backend capabilities.
- Redesigning initial setup, voice-profile editing, or diagnostic-log content beyond relocating their entry points.
- Adding phone PTT tap-to-toggle or slide-to-lock behavior.

## Decisions

### Use a stable root navigation shell

The application will use a root scaffold with persistent top-level access to `Radio` and `Settings`. `Radio` owns the operational dashboard. `Settings` owns durable configuration and administration. Secondary settings destinations use one consistent top app bar with visible Back navigation and return to the Settings hierarchy rather than jumping directly to Radio.

A two-section shell is preferred over another dashboard gear or management toggle because it gives configuration a stable location and can later admit a separate History section without restructuring every route. This change will not add an empty History destination.

### Define a Settings hierarchy by configuration ownership

The Settings home will group existing destinations as follows:

- **Devices and audio**: RSM setup/monitor, car headset configuration, and current phone-audio information.
- **Channels**: add, rename, reorder, and remove channel instances. Per-channel provider configuration remains reachable through an explicit settings action on each channel row and through the channel-management list.
- **Integrations and profiles**: installed provider packages, provider-published profiles, and voice profiles.
- **System**: permission, model, offline-voice, and storage readiness with existing recovery actions where available.
- **Advanced**: diagnostic log analysis and configuration.

The hierarchy reuses existing service actions and screens. Provider-specific channel settings remain instance-owned; global package/profile/device administration does not remain hidden inside the channel list.

A flat settings page containing every control was rejected because it would reproduce the current implementation-oriented card stack under a new title.

### Treat `InputMode` presentation as preferred audio-device selection

The service state remains `InputMode`; no second persisted preference is introduced. The dashboard presents the state as `Audio device` rather than `Talk from` and explains:

> Replies play on the selected device. Using another PTT switches automatically.

Manual tile selection continues to select any available mode for later playback admission. An admitted RSM, phone, or car PTT continues to call the existing actuator auto-transition before route acquisition. The UI updates the selected tile when that transition is published.

This is preferred over splitting input and output selections because the current product intentionally couples the actuator used to speak with the device used for subsequent replies.

Device tiles perform selection only. Setup and monitoring move to explicit Settings rows. Unavailable tiles remain visible and explain their state but do not hide setup behind long-press.

### Place phone PTT outside all scrolling content

The Radio root scaffold owns a fixed phone PTT region above the top-level navigation bar and safe-area inset. The dashboard content, including the channel list, scrolls independently behind that region. Dynamic labels and state changes may alter content inside the allocated region but must not change the region's bounds or center point.

A floating action button was rejected because its standard size and visual semantics understate a sustained, safety-sensitive PTT interaction. A dedicated full-width operational dock provides a larger target, channel context, and explicit capture state while retaining a fixed touch origin.

### Make the fixed control target only the active channel

The fixed control reads the active channel ID from the authoritative dashboard snapshot at gesture start. It never selects a channel. If there is no active channel, the region stays in place and presents a disabled `Choose a channel to talk` state. If the active channel is unavailable, pressing follows existing host-owned not-ready admission and feedback behavior.

Channel rows use ordinary selection semantics. Their primary surface changes only `activeChannelId`; it cannot start, lock, stop, or release PTT. Pending-message and channel-settings controls remain separate actions.

This separation prevents an accidental transmission from a selection gesture and keeps destination selection independent from transmission control.

### Use strict press-and-hold phone PTT

Pointer down on the enabled fixed control starts phone PTT; pointer up or cancellation releases it. Focus loss and lifecycle termination retain existing forced-release behavior. The former long-press delay, inward slide threshold, locked state, lock instruction, and card-local stop button are removed.

When phone PTT admission succeeds, existing service behavior auto-transitions to `OnAPinch`; later incoming playback therefore targets the phone until manual selection or another admitted actuator changes the mode.

Strict hold/release is preferred because it matches the physical radio model and creates one predictable terminal action. Hidden slide-lock was rejected because it weakens discoverability and makes a fixed PTT stateful without a dedicated mode indication.

### Keep the fixed region visible during non-phone activity

The region remains mounted while RSM or car capture, route acquisition, finalization, release, or channel playback is active. It displays the current operational state and rejects conflicting touch input according to existing admission policy; it does not move or disappear. For example, RSM capture is represented as `Recording on Radio`, while the phone control is unavailable until the active session terminates.

This preserves spatial memory and exposes capture ownership without allowing the phone surface to preempt another actuator.

### Expose accessibility semantics independently from pointer handling

The fixed control will expose button role, active channel, enabled/disabled state, capture state, and a descriptive PTT action to accessibility services. Pointer handling and semantics invoke the same phone PTT commands. State changes are announced without relying only on color. Channel rows expose selection semantics and selected state rather than PTT semantics.

### Perform a clean interaction cutover

The card pointer modifier, card-local gesture state, lock direction, locked state, and lock/stop composables will be removed rather than retained as alternate paths. Tests and specifications will be migrated to the fixed control; no compatibility switch or deprecated card gesture remains.

## Risks / Trade-offs

- **[Risk] The fixed dock reduces vertical space on small displays.** → Keep dashboard content independently scrollable, allocate one bounded dock height, and verify minimum-width, large-font, and navigation-inset layouts.
- **[Risk] Users may interpret audio-device selection as capture locking.** → Use output-oriented explanatory copy and immediately reflect actuator auto-switches in the selected tile.
- **[Risk] Moving administration can strand existing routes.** → Inventory every current `PttUiActions` navigation entry and provide one Settings destination before removing the dashboard management entry.
- **[Risk] Removing slide-lock makes very long phone messages harder.** → Preserve the existing maximum-duration and physical-device paths; tap-to-toggle or an explicit lock mode remains a separate future change.
- **[Risk] A fixed phone control can appear available during another source's session.** → Keep the region visible but stateful and disabled while capture admission is owned elsewhere.
- **[Risk] Flat route state can produce incorrect Back behavior inside Settings.** → Separate top-level section selection from secondary route state and define Back as secondary route → Settings home → Radio/system exit.

## Migration Plan

1. Add the root Radio/Settings navigation shell and Settings hierarchy while existing dashboard entry points still work.
2. Route every existing app-wide configuration and diagnostic destination through Settings, then remove hidden dashboard management and tile-long-press entry points.
3. Introduce the fixed PTT region wired to active-channel state and existing phone PTT actions.
4. Convert channel cards to selection-only surfaces and remove card-local phone PTT and slide-lock code in the same cutover.
5. Replace affected unit and Compose interaction tests, then smoke-test manual device selection, phone auto-switch, RSM auto-switch, car auto-switch, active-channel routing, focus-loss release, and cross-source admission.

No persisted data migration is required. Rollback is a code rollback because catalogue, mode, package, profile, and device stores are unchanged.

## Open Questions

None. History navigation, priority-channel configuration, and alternate phone capture modes remain intentionally separate changes.
