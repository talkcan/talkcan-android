## Purpose

Defines a push-to-talk speech-to-text test mode on the connected monitor/test surface that records Bluetooth SCO audio via the existing PTT-controlled path and transcribes it on-device with Parakeet v3, alongside the echo, TTS, and STT-to-TTS test modes.
## Requirements
### Requirement: Parakeet model loads on app startup
The system SHALL start loading the Parakeet v3 speech-to-text model after app startup through the supported ONNX Runtime Android Java API without performing ONNX Runtime environment access, session construction, model asset file I/O, or other blocking model work on the Android main thread. Model acquisition, vocabulary loading, session construction, and readiness publication SHALL happen on a background thread, and the app SHALL remain responsive while loading continues. The application SHALL NOT initialize or invoke a custom Parakeet Rust/JNI bridge.

#### Scenario: Startup begins model loading off the main thread
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system launches a background coroutine to initialize the Kotlin Parakeet ONNX engine
- **AND** the foreground service `onCreate` returns without waiting for initialization to complete
- **AND** the system does not access the ONNX environment, construct sessions, invoke a custom model JNI method, or perform model asset file I/O on the main thread
- **AND** the app remains responsive while initialization and loading continue

#### Scenario: Warm-run initialization does not block the main thread
- **WHEN** the app process starts on a device where the Parakeet model assets already exist in files storage and pass the current manifest verification
- **THEN** the foreground service `onCreate` completes without performing blocking ONNX Runtime work or file I/O on the main thread
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the STT model readiness field shows Loading until background initialization completes
- **AND** the STT model readiness field transitions to Ready or Failed after background initialization completes

#### Scenario: First-run model acquisition does not block the main thread
- **WHEN** the app process starts on a device where the Parakeet model assets are missing, incomplete, or fail current manifest verification
- **THEN** the system performs model acquisition and verification on a background thread
- **AND** the foreground service `onCreate` returns without waiting for acquisition to complete
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the app remains responsive while acquisition and subsequent model loading continue
- **AND** the STT model readiness field shows Loading until both acquisition and model loading complete

#### Scenario: Model becomes ready
- **WHEN** every required Parakeet ONNX session, vocabulary, and decoder resource loads successfully on the background thread
- **THEN** the system atomically marks STT model readiness as ready
- **AND** subsequent STT test recordings can be transcribed without reloading the model

#### Scenario: Model load fails
- **WHEN** Parakeet model loading fails on the background thread
- **THEN** the system marks STT model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring STT requests
- **AND** every partially constructed ONNX session and value is closed

#### Scenario: Initialization failure leaves controllers unavailable without crashing
- **WHEN** the ONNX Runtime environment or Kotlin Parakeet engine cannot be constructed before a pollable engine is available
- **THEN** the system logs the failure without crashing the foreground service
- **AND** the STT controller is not constructed
- **AND** the STT model readiness poller is not started
- **AND** the connected monitor/test surface continues to show Loading for the STT model readiness field

### Requirement: STT test control is available in Debug Channel configuration
The system SHALL show the speech-to-text test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the STT test mode as a selectable option among the debug modes
- **AND** if STT mode is selected, the system shows a transcript text box below the selection controls

#### Scenario: No transcript is available yet
- **WHEN** the Debug Channel configuration screen is displayed before any STT result exists and STT is the active mode
- **THEN** the STT transcript text box shows an empty or idle STT state

### Requirement: STT test records audio from PTT activity
The system SHALL record audio for STT using the same PTT-controlled Bluetooth SCO recording path as the echo test.

#### Scenario: PTT is pressed while STT is enabled
- **WHEN** STT test mode is enabled and the user presses PTT
- **THEN** the system acquires the Bluetooth SCO route
- **AND** the system plays the ready beep through the selected communication audio route
- **AND** the system starts recording mono PCM audio for transcription

#### Scenario: PTT is released after audio is recorded
- **WHEN** STT test mode is enabled, recording is active, and the user releases PTT
- **THEN** the system stops recording
- **AND** the system submits the captured audio to the local Parakeet transcriber
- **AND** the system does not play the captured audio back as echo output

#### Scenario: PTT is released before recording starts
- **WHEN** STT test mode is enabled and the user releases PTT before recording starts
- **THEN** the system cancels the pending STT recording session
- **AND** the system does not submit empty audio to Parakeet

#### Scenario: Recording reaches maximum duration
- **WHEN** STT test mode is enabled and the user holds PTT past the maximum recording duration
- **THEN** the system stops recording at the maximum duration
- **AND** the system waits for PTT release before submitting the retained recording to Parakeet

### Requirement: STT transcription is local and result-driven
The system SHALL transcribe captured STT test audio on-device with Parakeet and expose the resulting text in app state.

#### Scenario: Captured audio is transcribed successfully
- **WHEN** STT test mode submits captured audio and Parakeet returns text
- **THEN** the system stores the returned text as the latest STT transcript
- **AND** the connected monitor/test surface shows that transcript in the STT transcript text box

#### Scenario: Captured audio contains no samples
- **WHEN** STT test mode stops recording and the captured audio is empty
- **THEN** the system does not invoke Parakeet
- **AND** the connected monitor/test surface shows an empty-audio STT status

#### Scenario: Model is still loading when audio is submitted
- **WHEN** STT test mode submits captured audio while Parakeet model loading is still in progress
- **THEN** the system waits for model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model or transcribing status

#### Scenario: Transcription fails
- **WHEN** Parakeet transcription returns an error
- **THEN** the system stores an STT error status
- **AND** the connected monitor/test surface shows the error instead of stale transcript text

#### Scenario: Audio would leave the device
- **WHEN** STT test mode processes captured audio
- **THEN** the system MUST NOT send the captured audio or transcript to a network service

### Requirement: STT sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending STT recording work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during STT work
- **WHEN** STT test mode has active or pending recording or transcription work and the serial connection is disconnected
- **THEN** the system cancels active STT recording work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no STT recording active after disconnect completes

#### Scenario: STT mode is disabled during recording
- **WHEN** STT test mode is recording and the user disables STT test mode
- **THEN** the system stops the STT recording session
- **AND** the system does not submit the cancelled recording to Parakeet

#### Scenario: Echo mode is enabled during STT work
- **WHEN** STT test mode has active or pending recording work and the user enables echo test mode
- **THEN** the system cancels STT recording work
- **AND** the system enables echo test mode

