## Purpose

TBD. Defines the on-the-road PTT session lifecycle: Telecom self-call per cycle, mandatory route switch on release, A2DP response playback, 30-second idle timeout, and hang-up as the authoritative release signal.

## Requirements

### Requirement: On-the-road PTT cycle uses call-per-cycle with mandatory route switch
The system SHALL implement each on-the-road PTT capture as a complete Telecom self-call lifecycle: place call, acquire SCO, capture, release on hang-up, and perform a mandatory route switch that drops SCO and ends the call before allowing the next cycle.

#### Scenario: Complete PTT cycle
- **WHEN** the user presses play/pause on the steering wheel while in `OnTheRoad` mode and the active channel is ready
- **THEN** the system SHALL place a self-managed Telecom call
- **AND** wait for Bluetooth call audio route to become active
- **AND** play a ready beep through call audio
- **AND** start microphone capture
- **WHEN** the user presses hang-up
- **THEN** the system SHALL stop capture
- **AND** trigger the route switch (release SCO, end call, switch to A2DP)
- **AND** the car SHALL return to normal media mode with media controls available

#### Scenario: Route switch without response audio
- **WHEN** a PTT capture ends on the on-the-road path and no response audio is available to play
- **THEN** the system SHALL still trigger the route switch to drop SCO and end the call
- **AND** no audible response SHALL be played
- **AND** the car SHALL return to normal media mode

### Requirement: Response audio plays via A2DP after SCO drop
The system SHALL play any post-capture response audio through the car's media audio path (A2DP) after the Telecom call and SCO link have been released.

#### Scenario: Response playback after capture
- **WHEN** a PTT capture has ended and response audio is available
- **AND** the route switch has completed (SCO dropped, call ended, audio mode returned to normal)
- **THEN** the system SHALL request media audio focus
- **AND** play the response through the media output path (USAGE_MEDIA)
- **AND** abandon media audio focus after playback completes

#### Scenario: No response audio
- **WHEN** a PTT capture has ended and no response audio is available
- **THEN** the system SHALL complete the route switch without playing any audio
- **AND** media controls SHALL be available for the next PTT cycle

### Requirement: SCO must be released on every PTT release
The system SHALL release the SCO audio link on every on-the-road PTT release, regardless of whether the channel produces response audio. Failing to release SCO causes the car to attempt a redial.

#### Scenario: SCO released after channel capture without playback
- **WHEN** a channel (such as Journal) completes a PTT capture on the on-the-road path
- **AND** the channel does not play back the recording (it writes to disk instead)
- **THEN** the system SHALL still trigger the route switch to release SCO and end the call
- **AND** the car SHALL NOT redial

#### Scenario: SCO released after channel capture with playback
- **WHEN** a channel (such as Echo) completes a PTT capture on the on-the-road path
- **AND** the channel plays back the recording
- **THEN** the system SHALL release SCO before playing the response via A2DP
- **AND** the car SHALL NOT redial

### Requirement: 30-second idle timeout after route switch
The system SHALL start a 30-second idle timer after the route switch completes. If no new PTT cycle begins within 30 seconds, the on-the-road session resources SHALL be cleaned up.

#### Scenario: Idle timeout fires
- **WHEN** the route switch has completed and 30 seconds pass without a new play/pause press
- **THEN** the system SHALL clean up on-the-road session resources
- **AND** remain in `OnTheRoad` mode if Android Auto is still connected

#### Scenario: Idle timer cancelled by new PTT
- **WHEN** the user presses play/pause before the 30-second idle timer fires
- **THEN** the system SHALL cancel the idle timer
- **AND** start a new PTT cycle

### Requirement: No media-start suppress window
The system SHALL NOT apply a suppress window that blocks media-start events after a PTT release. The mandatory route switch makes the suppress window unnecessary.

#### Scenario: Play/pause immediately after release
- **WHEN** the route switch has completed and the user presses play/pause immediately
- **THEN** the system SHALL accept the press and start a new PTT cycle
- **AND** SHALL NOT suppress the press based on a time window

### Requirement: Hang-up is the release signal
The system SHALL treat the car's hang-up button (delivered as `Connection.onDisconnect()`) as the authoritative PTT release signal on the on-the-road path.

#### Scenario: Hang-up ends capture
- **WHEN** a capture is in progress on the on-the-road path
- **AND** `Connection.onDisconnect()` fires
- **THEN** the system SHALL stop capture, finalize the PTT session, and trigger the route switch

#### Scenario: Button presses during no-call SCO-active phase are unreachable
- **WHEN** the Telecom call has ended but SCO has not been released
- **AND** the user presses hang-up or play/pause
- **THEN** the button event SHALL be delivered as `KEYCODE_UNKNOWN` to the Android Auto layer
- **AND** SHALL NOT reach Talkcan
- **AND** this confirms that SCO must be released to make media controls available again
