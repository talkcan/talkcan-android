## REMOVED Requirements

### Requirement: Channel-card long-press starts PTT
**Reason**: Channel cards become destination selectors only; a single fixed phone PTT control provides stable spatial memory and targets the active channel.

**Migration**: Tap a channel card to make it active, then press and hold the fixed phone PTT control.

### Requirement: Phone PTT supports inward slide-to-lock
**Reason**: The fixed phone PTT uses strict press-and-hold behavior and removes the hidden, stateful slide-lock gesture.

**Migration**: Continue holding the fixed phone PTT control while speaking and release it to finish the message.

### Requirement: PTT source is independent from audio route
**Reason**: The old requirement described channel-card phone PTT with opportunistic RSM routing, which conflicts with the authoritative actuator home-mode model.

**Migration**: Fixed phone PTT auto-selects `OnAPinch`; RSM and car actuators auto-select their own home modes before route acquisition.

### Requirement: Phone-originated PTT preserves session timing
**Reason**: Phone PTT timing is redefined around immediate pointer-down on the fixed control rather than a channel-card long-press and optional lock state.

**Migration**: Use the `fixed-phone-ptt` timing contract for ready beep, release cancellation, maximum duration, focus loss, and teardown.

### Requirement: Phone-originated PTT supports every functional channel
**Reason**: Per-card PTT surfaces are removed; functional channel coverage now follows the active-channel destination of the single fixed control.

**Migration**: Select any functional channel, then use fixed phone PTT.

### Requirement: Phone PTT is not terminated by unrelated device lifecycles
**Reason**: The lifecycle guarantee remains required but is owned by the new fixed phone PTT capability rather than the removed card gesture.

**Migration**: Use the equivalent lifecycle requirements and scenarios in `fixed-phone-ptt`.
