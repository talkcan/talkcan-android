## 1. Navigation State And Settings Shell

- [x] 1.1 Replace the flat dashboard-only route state with separate Radio/Settings top-level section state and secondary destination state, preserving existing service and catalogue state across section changes.
- [x] 1.2 Extend `PttUiActions` and `MainActivity` navigation wiring with explicit Settings-home, Settings-child, Radio, and hierarchical Back actions; remove route exits that always jump directly to the dashboard.
- [x] 1.3 Add the root Material 3 navigation scaffold with persistent Radio and Settings destinations, safe-area handling, and no placeholder History destination.
- [x] 1.4 Add a Settings home screen grouped into Devices and audio, Channels, Integrations and profiles, System, and Advanced destinations.
- [x] 1.5 Give every secondary Settings destination one consistent top app bar and visible Back behavior returning through the Settings hierarchy.

## 2. Move Existing Configuration Into Settings

- [x] 2.1 Extract channel add, rename, reorder, and remove controls from `MainDashboardScreen` into a Settings-owned channel-management screen without changing catalogue mutation semantics.
- [x] 2.2 Route RSM setup/monitoring and car-headset configuration through explicit Devices and audio rows, including unavailable-device setup paths.
- [x] 2.3 Route installed packages, generic provider profiles, and voice profiles through Integrations and profiles.
- [x] 2.4 Expose existing permission, model, offline-voice, and storage readiness or recovery actions under System without creating new backend state.
- [x] 2.5 Move Log Analysis access to Advanced and remove the hidden application-title diagnostic shortcut.
- [x] 2.6 Remove the dashboard `Manage` mode and obsolete package/profile navigation buttons after every displaced destination is reachable from Settings.

## 3. Audio-Device Selector Semantics

- [x] 3.1 Rename the dashboard `Talk from` presentation to `Audio device`, describe selected-device playback behavior, and explain actuator auto-switching.
- [x] 3.2 Make Radio, Car, and Phone tiles selection-only controls with selected and unavailable accessibility state; remove mode-tile long-press setup dispatch.
- [x] 3.3 Keep unavailable devices visible and direct their setup through explicit Settings rows rather than changing tile tap semantics.
- [x] 3.4 Confirm published `InputMode` changes from phone, RSM, and car PTT immediately update the selected audio-device tile without introducing a second preference store.

## 4. Fixed Phone PTT State And Gesture

- [x] 4.1 Refactor phone PTT gesture state to target the authoritative active channel and support immediate pointer-down, release, cancellation, focus-loss, and terminal lifecycle transitions without card identity or lock direction.
- [x] 4.2 Remove slide-to-lock, locked gesture state, inward threshold logic, lock instructions, and explicit locked-stop handling from the phone PTT interaction model.
- [x] 4.3 Implement a fixed phone PTT operational dock outside dashboard scrolling content and above root navigation and safe-area insets.
- [x] 4.4 Wire the dock to existing `phonePttPressed` and `phonePttReleased` actions so admitted phone PTT auto-transitions to `OnAPinch` and dispatches only to the active channel.
- [x] 4.5 Keep the dock mounted with stable bounds for no-selection, unavailable-channel, playback, pending capture, recording, finalization, release, RSM-owned capture, and car-owned capture states.
- [x] 4.6 Add button role, destination, enabled state, selected channel, capture ownership, and state descriptions to the dock without relying on color alone.

## 5. Selection-Only Channel Cards

- [x] 5.1 Replace channel-card pointer PTT handling with an ordinary selection action that only changes the active channel.
- [x] 5.2 Remove card-local phone PTT state, held/locked guidance, stop controls, gesture parameters, and PTT-specific card coloring while retaining readiness, execution, pending-message, and playback projections.
- [x] 5.3 Preserve separate pending-message and channel-configuration actions without letting either action select unintentionally or invoke phone PTT.
- [x] 5.4 Remove obsolete channel-card PTT helpers and all migrated call sites from main, test, and androidTest source sets.

## 6. End-To-End Smoke Verification

- [ ] 6.1 Launch the app and verify Radio/Settings navigation, all Settings groups, every relocated destination, and visible/system Back behavior end to end.
- [ ] 6.2 Select, scroll, reorder, and change the runtime state of channels while confirming the phone PTT dock remains at one fixed screen position and always labels the active channel.
- [ ] 6.3 Exercise fixed phone PTT press, ready-beep release cancellation, recording release, focus loss, unavailable-channel feedback, and cross-source admission on the phone route.
- [ ] 6.4 Exercise RSM and car PTT while the app is visible and confirm each actuator auto-selects its home audio device, updates the selector, owns the dock state, and leaves later incoming playback on the selected endpoint.

## 7. Contract Verification

 - [x] 7.1 Replace obsolete channel-card and slide-lock unit tests with fixed-control active-channel, strict-hold, cancellation, lifecycle, and cross-source ownership tests.
- [x] 7.2 Add navigation-state tests covering Radio/Settings switching, Settings-child Back behavior, and preservation of active channel and selected audio device.
- [x] 7.3 Add Compose interaction tests proving channel rows select without starting PTT, device tiles have no hidden setup gesture, Settings destinations are reachable, and the PTT dock bounds remain stable while content scrolls.
- [ ] 7.4 Run the targeted unit and instrumentation tests covering navigation, dashboard, input-mode auto-switch, PTT routing, and playback routing, then run the application build.
