## Purpose

TBD. Defines the process-wide half-duplex audio coordinator that serializes capture and channel-content playback across Work, On-the-road, and On-a-pinch, integrating with the existing input subsystem through capture-admission and terminal-completion boundaries.

## Requirements

### Requirement: Host audio is process-wide half-duplex
The host SHALL admit at most one capture, channel-content playback, or system-navigation playback operation at a time across Work, On-the-road, and On-a-pinch. Capture reservation, capture route acquisition, recording, capture finalization, playback reservation, playback route acquisition, playback, and their route-release cleanup SHALL be mutually exclusive across operations, and no channel runtime SHALL participate in that arbitration. Canceling an owning playback caller SHALL be terminal and teardown-ordered: the host SHALL stop that exact `ActivePcmPlayback`, publish terminal completion only after its `AudioTrack` has been stopped and released, release the acquired playback route, and clear only the matching coordinator owner before the canceling caller's join completes and before a replacement operation is admitted.

#### Scenario: Response becomes ready during capture
- **WHEN** a channel response becomes playable while any PTT capture is reserved, acquiring a route, recording, finalizing, or releasing its route
- **THEN** the host SHALL leave the response pending and unheard
- **AND** it SHALL NOT acquire any playback route until capture terminal cleanup publishes that audio admission is free

#### Scenario: Capture admission wins before playback
- **WHEN** a PTT operation has obtained a capture reservation but has not yet opened its recorder or acquired its route
- **AND** a pending response becomes eligible
- **THEN** playback admission SHALL remain blocked until the reserved capture operation completes its terminal cleanup

#### Scenario: Playback route cleanup blocks capture
- **WHEN** response audio has stopped but its playback route or focus cleanup has not completed
- **THEN** the host SHALL continue treating audio as playback-owned
- **AND** it SHALL NOT admit a capture operation until cleanup completes

#### Scenario: Canceling an owning playback stops that exact playback
- **WHEN** the sole owning caller cancels its playback operation
- **THEN** the host SHALL stop that exact `ActivePcmPlayback`
- **AND** it SHALL NOT stop, skip, or release any other `ActivePcmPlayback` or any capture reservation or route

#### Scenario: Terminal completion is published after AudioTrack teardown
- **WHEN** an owning playback is canceled or naturally completes
- **THEN** the host SHALL publish terminal completion only after the owning `AudioTrack` has been stopped and released
- **AND** the coordinator SHALL NOT admit a replacement operation until that terminal completion is published

#### Scenario: Route cleanup and matching-owner clear complete before join
- **WHEN** a canceling caller joins its owning playback job
- **THEN** the host SHALL release the acquired playback route and clear only the matching coordinator owner before the join returns
- **AND** a replacement operation SHALL NOT acquire audio admission until that clear is visible

### Requirement: Existing input subsystem mechanics remain authoritative
The half-duplex coordinator SHALL integrate outside the existing input subsystem through capture-admission and terminal-completion boundaries. It SHALL NOT redesign, reorder, replace, or weaken `InputModeController` mode selection, admissible actuator auto-transition, `PttAudioSessionManager` terminal ownership and exact-once cleanup, route gates, `CaptureService` recorder acquisition and preflight, ready-beep timing, channel-visible frame delivery, or the existing Work, Telecom, and local capture strategies.

#### Scenario: Admissible PTT follows the existing input path
- **WHEN** audio is not owned by playback and a PTT press is otherwise admissible
- **THEN** the host SHALL run the existing actuator transition, route resolution, capture setup, ready beep, recording, terminal notification, and cleanup sequence without reordering those effects

#### Scenario: Playback integration observes capture completion
- **WHEN** an existing input session finishes its authoritative terminal sequence
- **THEN** the integration SHALL use the existing terminal-completion publication to wake pending playback
- **AND** it SHALL NOT introduce a second route-release owner or terminal-cleanup path

### Requirement: Pending responses remain device-unbound until playback admission
A response awaiting playback SHALL retain channel identity, durable message identity, text, lifecycle, and FIFO order without retaining an originating `InputMode`, physical endpoint, Android audio object, `ScoRoute`, `PcmOutput`, recorder, or input-session route. After admission and immediately before route acquisition, the host SHALL revalidate active channel selection and snapshot the current selected mode.

#### Scenario: Mode changes while response waits
- **WHEN** a response becomes pending after a turn captured in one mode
- **AND** the user selects another available mode before playback admission
- **THEN** the host SHALL resolve playback from the newly selected mode
- **AND** it SHALL NOT reuse the originating capture route

#### Scenario: Multiple responses accumulate during recording
- **WHEN** multiple responses for the selected channel become pending while recording owns audio
- **THEN** the host SHALL retain every response pending and unheard in durable arrival order
- **AND** it SHALL consider them for playback in that same order after audio becomes free

### Requirement: PTT is rejected with in-route feedback during active playback
While channel-content playback is actively audible, every RSM, car, or phone PTT press SHALL be rejected before actuator auto-transition or capture reservation. The active playback owner SHALL duck speech, overlay at most one debounced error beep through its already-acquired output stream, restore speech, continue the same response, consume the corresponding PTT release, and leave the selected mode unchanged.

#### Scenario: RSM PTT is pressed during playback
- **WHEN** an RSM PTT press occurs while channel-content playback is active
- **THEN** the host SHALL NOT reserve or start capture
- **AND** it SHALL NOT auto-transition the selected mode to Work
- **AND** the active output SHALL duck speech, overlay the rejection beep, restore speech, and continue playback

#### Scenario: Phone or car PTT is pressed during playback
- **WHEN** phone or car PTT is pressed while channel-content playback is active
- **THEN** the host SHALL reject capture before changing the selected mode
- **AND** it SHALL emit the same rejection feedback through the active playback route rather than the actuator's home endpoint

#### Scenario: Repeated presses occur during one rejection tone
- **WHEN** additional PTT press signals arrive while the current rejection beep is active
- **THEN** the host SHALL consume them without queuing or overlapping additional rejection beeps
- **AND** one physical press SHALL produce at most one rejection beep

### Requirement: RSM SOS contextually skips active playback and pauses its queue
An RSM SOS press during active channel-content playback SHALL stop that playback, mark only its message heard by explicit skip, pause automatic playback admission for later pending responses belonging to that channel, and consume the SOS without resetting the channel conversation. When no playback operation is active or completing, SOS SHALL retain its existing channel-level behavior.

#### Scenario: SOS skips an active response
- **WHEN** RSM SOS is pressed during active response playback
- **THEN** the host SHALL stop playback and finish route cleanup
- **AND** it SHALL atomically mark the active response heard by explicit skip
- **AND** it SHALL leave later responses pending and unheard
- **AND** it SHALL pause automatic queue draining for that channel

#### Scenario: SOS during playback does not reset conversation
- **WHEN** SOS is consumed as an active-playback skip
- **THEN** the host SHALL NOT dispatch that SOS to the selected channel runtime
- **AND** the agent's volatile conversation epoch SHALL remain unchanged

#### Scenario: SOS outside playback keeps existing behavior
- **WHEN** RSM SOS is pressed while no playback operation is active or completing
- **THEN** the host SHALL dispatch the existing channel-level SOS action

#### Scenario: SOS races natural completion
- **WHEN** SOS and natural playback completion occur concurrently
- **THEN** exactly one monotonic heard transition SHALL be committed
- **AND** SOS SHALL be consumed as playback control while the playback operation still owns completion or cleanup

### Requirement: A paused response queue resumes only by same-channel reselection
A queue paused by an SOS playback skip SHALL remain paused across new response arrival, audio becoming idle, endpoint recovery, mode changes, and ordinary scheduler wakeups. It SHALL resume only when a control surface deliberately selects that same channel again through the shared active-channel selection path.

#### Scenario: Later response arrives while queue is paused
- **WHEN** a response becomes pending for a channel whose playback queue is paused
- **THEN** the host SHALL retain it pending and unheard
- **AND** it SHALL NOT resume playback solely because the new response arrived

#### Scenario: User reselects the paused channel
- **WHEN** the user selects the same channel again through phone, RSM, or car controls
- **THEN** the host SHALL clear that channel's paused-drain state
- **AND** it SHALL make its pending responses eligible in durable FIFO order when half-duplex audio admission and the selected mode route are available

#### Scenario: Another channel is selected
- **WHEN** a different channel is selected while the original channel's queue is paused
- **THEN** the original channel's responses SHALL remain pending
- **AND** selecting the different channel SHALL NOT clear the original channel's paused state

### Requirement: Navigation cancellation is scoped to its own prior playback
Navigation SHALL cancel only its own tracked prior navigation playback job when that job is the one that owns audio admission. When capture or an unrelated playback operation owns admission, no navigation-owned playback exists to cancel and a new navigation request SHALL respect `Busy` or remain pending until audio admission is free. Navigation cancellation SHALL NOT stop, skip, or preempt any unrelated playback operation, capture reservation, capture route, or capture finalization.

#### Scenario: Navigation cancels its own prior announcement
- **WHEN** navigation starts a new announcement while its prior announcement playback is the one that owns audio admission and is still active or completing
- **THEN** the host SHALL cancel only the prior navigation playback job
- **AND** the new announcement SHALL wait for that cancellation's teardown to complete before acquiring audio admission

#### Scenario: Navigation respects Busy while capture or unrelated playback owns admission
- **WHEN** navigation issues a request while capture or an unrelated channel-content playback owns audio admission
- **THEN** no navigation-owned playback exists to cancel
- **AND** the navigation request SHALL receive `Busy` or remain pending until audio admission is free
- **AND** the host SHALL NOT stop, skip, or release the owning capture or unrelated playback operation

#### Scenario: Navigation cancellation does not preempt capture
- **WHEN** navigation cancels its prior playback during that playback's teardown while a capture operation is pending admission
- **THEN** the host SHALL NOT interrupt, stop, or release the pending capture operation
- **AND** the capture operation SHALL acquire admission only after the cancellation teardown completes and the coordinator owner is cleared

### Requirement: PTT and SOS control remain authoritative during playback cancellation
The existing PTT rejection, rejection-tone debounce, rejection-release consumption, RSM SOS skip, SOS conversation preservation, and SOS race resolution semantics SHALL remain authoritative. Navigation cancellation SHALL NOT bypass, weaken, or reorder those control paths. While any playback cancellation teardown has not yet cleared the coordinator owner, the host SHALL continue treating audio as playback-owned and SHALL NOT admit capture.

#### Scenario: Capture admission is blocked during cancellation teardown
- **WHEN** a PTT press arrives while any playback cancellation is performing teardown but has not yet released the coordinator owner
- **THEN** the host SHALL continue treating audio as playback-owned
- **AND** it SHALL NOT admit capture until the cancellation teardown completes and the coordinator owner is cleared

#### Scenario: Navigation cancellation does not bypass PTT or SOS control
- **WHEN** navigation cancels its prior playback during the teardown of that playback
- **THEN** the existing PTT rejection, rejection-release consumption, and SOS skip paths SHALL remain unchanged
- **AND** the navigation cancellation SHALL NOT introduce a second route-release owner or terminal-cleanup path that bypasses those controls