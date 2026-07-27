## Purpose

Defines a push-to-talk speech-to-text-to-speech round-trip test mode on the connected monitor/test surface that records Bluetooth SCO audio, transcribes it on-device with Parakeet v3, synthesizes the transcript on-device with Supertonic 3, and plays the result through the connected Bluetooth SCO route.
## Requirements
### Requirement: STT↔TTS test control is available in Debug Channel configuration
The system SHALL show the speech-to-text-to-speech round-trip test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the STT↔TTS test mode as a selectable option among the debug modes
- **AND** if STT↔TTS mode is selected, below the selection the system shows a status/transcript area and no text input box

#### Scenario: Round-trip test is not yet run
- **WHEN** the Debug Channel configuration screen is displayed before any STT↔TTS result exists and STT↔TTS is the active mode
- **THEN** the status/transcript area shows an idle state

### Requirement: STT↔TTS test records audio from PTT activity
The system SHALL record audio for the STT↔TTS round-trip using the same PTT-controlled Bluetooth SCO recording path as the STT test.

#### Scenario: PTT is pressed while STT↔TTS is enabled
- **WHEN** STT↔TTS test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the selected communication audio route
- **AND** the system starts recording mono PCM audio

#### Scenario: PTT is released after audio is recorded
- **WHEN** STT↔TTS test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system submits the captured audio to the local Parakeet transcriber
- **AND** the system does not play the captured audio back as echo

#### Scenario: PTT is released before recording starts
- **WHEN** STT↔TTS test mode is enabled and the user releases PTT before recording starts
- **THEN** the system cancels the pending recording session
- **AND** the system does not submit empty audio to Parakeet

#### Scenario: Recording reaches maximum duration
- **WHEN** STT↔TTS test mode is enabled and the user holds PTT past the maximum recording duration
- **THEN** the system stops recording at the maximum duration
- **AND** the system waits for PTT release before submitting the retained recording

### Requirement: STT↔TTS test transcribes then synthesizes then plays back
The system SHALL transcribe captured STT↔TTS audio on-device with Parakeet, synthesize speech from the resulting transcript on-device with Supertonic, and play the synthesized audio through the connected Bluetooth SCO route.

#### Scenario: Round-trip completes successfully
- **WHEN** STT↔TTS test mode submits captured audio and Parakeet returns text and Supertonic returns audio for that text
- **THEN** the system stores the transcript as the latest STT↔TTS transcript
- **AND** the system shows a transcribing status during transcription and a synthesizing status during synthesis
- **AND** the system resamples the 44.1 kHz Supertonic output to the SCO output rate
- **AND** the system plays the synthesized audio through the headset
- **AND** the connected monitor/test surface shows the transcript in the STT↔TTS status/transcript area

#### Scenario: Captured audio contains no samples
- **WHEN** STT↔TTS test mode stops recording and the captured audio is empty
- **THEN** the system does not invoke Parakeet
- **AND** the system shows an empty-audio status

#### Scenario: Transcription returns empty text
- **WHEN** Parakeet returns an empty transcript for the captured audio
- **THEN** the system does not invoke Supertonic
- **AND** the system shows an empty-transcript status

#### Scenario: Transcription fails
- **WHEN** Parakeet transcription returns an error
- **THEN** the system shows a transcription error status
- **AND** the system does not invoke Supertonic

#### Scenario: Synthesis fails
- **WHEN** Supertonic synthesis returns an error for the transcript
- **THEN** the system shows a synthesis error status
- **AND** the system does not play audio

#### Scenario: A model is still loading when its stage runs
- **WHEN** STT↔TTS test mode reaches the transcription stage while Parakeet is still loading, or reaches the synthesis stage while Supertonic is still loading
- **THEN** the system waits for the required model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model status for the relevant stage

#### Scenario: Audio or text would leave the device
- **WHEN** STT↔TTS test mode processes captured audio, transcripts, or synthesized audio
- **THEN** the system MUST NOT send the captured audio, transcript, or synthesized audio to a network service

### Requirement: STT↔TTS sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending STT↔TTS recording, transcription, synthesis, or playback work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during STT↔TTS work
- **WHEN** STT↔TTS test mode has active or pending work and the serial connection is disconnected
- **THEN** the system cancels active STT↔TTS work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no STT↔TTS work active after disconnect completes

#### Scenario: Another mode is enabled during STT↔TTS work
- **WHEN** STT↔TTS test mode has active or pending work and the user enables echo, STT, or TTS test mode
- **THEN** the system cancels STT↔TTS work
- **AND** the system enables the newly selected mode

### Requirement: Echo test records audio from PTT activity

The system SHALL record audio for echo using the same PTT-controlled Bluetooth SCO recording path as the other test modes.

#### Scenario: PTT is pressed while echo is enabled
- **WHEN** echo test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the Bluetooth SCO route
- **AND** the system waits for the ready beep to finish playing
- **AND** the system starts recording mono PCM audio for echo playback
- **AND** the recording SHALL include the tail of the ready beep at its start (beep is audible in the recorded playback)

#### Scenario: PTT is released before recording starts
- **WHEN** echo test mode is enabled and the user releases PTT before recording starts (during SCO acquisition or beep playback)
- **THEN** the system cancels the pending echo session
- **AND** the system retains the SCO route warm for the warmup window
- **AND** the system does not play back any audio
- **AND** the system does not submit audio for echo playback

#### Scenario: PTT is released after audio is recorded
- **WHEN** echo test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system stops the SCO warmup timer
- **AND** the system plays back the captured audio through the Bluetooth SCO route
- **AND** the system restarts the SCO warmup timer after playback completes

### Requirement: SCO is pre-warmed on short PTT taps

The system SHALL retain the warm SCO route when PTT is released before a recording session starts, so that the next PTT press gets instant audio path availability.

#### Scenario: Quick PTT tap in echo mode
- **WHEN** echo test mode is enabled, the user quickly presses and releases PTT before SCO acquisition completes, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system retains the SCO route warm for the 30-second warmup window
- **AND** the user perceives no echo session

#### Scenario: Quick PTT tap in STT mode
- **WHEN** STT test mode is enabled, the user quickly presses and releases PTT, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system does not submit audio to Parakeet
- **AND** the system retains the SCO route warm for the 30-second warmup window

#### Scenario: Quick PTT tap in STT↔TTS mode
- **WHEN** STT↔TTS test mode is enabled, the user quickly presses and releases PTT, and SCO acquisition then completes
- **THEN** the system does not play the ready beep
- **AND** the system does not start recording
- **AND** the system does not transcribe or synthesize
- **AND** the system retains the SCO route warm for the 30-second warmup window

### Requirement: Echo status shows Warm state after short-tap cancellation
The system SHALL surface a warm state in the echo status area when an echo session is cancelled before recording begins but the SCO route is retained warm.

#### Scenario: Echo shows Warm on short tap
- **WHEN** echo test mode is enabled, the user taps PTT briefly, SCO acquisition completes, and the session is cancelled pre-recording
- **THEN** the echo status transitions to `EchoStatus.Warm` for the duration of the SCO warmup window
- **AND** the UI shows the warm state in the echo status area

### Requirement: STT↔TTS test mode becomes available after both engines initialize, regardless of completion order
The system SHALL construct the STT↔TTS round-trip controller once both the Parakeet (STT) and Supertonic (TTS) engines have finished initializing, regardless of which engine's initialization completes first, so that STT↔TTS test mode is usable on first run without restarting the app. If either engine's initialization fails, the system SHALL NOT construct the STT↔TTS controller.

#### Scenario: STT initializes first, then TTS
- **WHEN** the app starts, the Parakeet engine finishes initializing, and then the Supertonic engine finishes initializing
- **THEN** the system constructs the STT↔TTS controller
- **AND** STT↔TTS test mode is usable without restarting the app

#### Scenario: TTS initializes first, then STT
- **WHEN** the app starts, the Supertonic engine finishes initializing, and then the Parakeet engine finishes initializing
- **THEN** the system constructs the STT↔TTS controller once the Parakeet engine finishes
- **AND** STT↔TTS test mode is usable without restarting the app

#### Scenario: STT initialization fails
- **WHEN** the Parakeet engine initialization fails, regardless of whether Supertonic initialization succeeds or fails
- **THEN** the system does not construct the STT↔TTS controller
- **AND** the system does not crash
- **AND** the other test modes that do not require the Parakeet engine remain available

#### Scenario: TTS initialization fails
- **WHEN** the Supertonic engine initialization fails, regardless of whether Parakeet initialization succeeds or fails
- **THEN** the system does not construct the STT↔TTS controller
- **AND** the system does not crash
- **AND** the other test modes that do not require the Supertonic engine remain available

#### Scenario: STT↔TTS used before both engines are ready
- **WHEN** STT↔TTS test mode is active and the user triggers PTT before both engines have finished initializing
- **THEN** the system does not crash
- **AND** the system does not block the UI thread waiting for engine readiness

