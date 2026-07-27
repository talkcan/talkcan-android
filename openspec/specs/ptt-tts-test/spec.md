## Purpose

Defines a push-to-talk text-to-speech test mode on the connected monitor/test surface that loads Supertonic 3 on device, exposes supported synthesis parameters, and plays synthesized speech through the connected Bluetooth SCO route.
## Requirements
### Requirement: Supertonic model loads on app startup
The system SHALL start loading the Supertonic v3 text-to-speech model after app startup through the supported ONNX Runtime Android Java API without performing ONNX Runtime environment access, session construction, model asset file I/O, or other blocking model work on the Android main thread. Model acquisition, configuration and text-resource loading, session construction, and readiness publication SHALL happen on a background thread, and the app SHALL remain responsive while loading continues. The application SHALL NOT initialize or invoke a custom Supertonic Rust/JNI bridge.

#### Scenario: Startup begins TTS model loading off the main thread
- **WHEN** the app process starts and the foreground service is created by the launched app
- **THEN** the system launches a background coroutine to initialize the Kotlin Supertonic ONNX engine
- **AND** the foreground service `onCreate` returns without waiting for initialization to complete
- **AND** the system does not access the ONNX environment, construct sessions, invoke a custom model JNI method, or perform model asset file I/O on the main thread
- **AND** the app remains responsive while initialization and loading continue

#### Scenario: Warm-run TTS initialization does not block the main thread
- **WHEN** the app process starts on a device where the Supertonic model assets already exist in files storage and pass the current manifest verification
- **THEN** the foreground service `onCreate` completes without performing blocking ONNX Runtime work or file I/O on the main thread
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the TTS model readiness field shows Loading until background initialization completes
- **AND** the TTS model readiness field transitions to Ready or Failed after background initialization completes

#### Scenario: First-run TTS model acquisition does not block the main thread
- **WHEN** the app process starts on a device where the Supertonic model assets are missing, incomplete, or fail current manifest verification
- **THEN** the system performs model acquisition and verification on a background thread
- **AND** the foreground service `onCreate` returns without waiting for acquisition to complete
- **AND** the foreground service notification posts within the foreground-service ANR threshold
- **AND** the app remains responsive while acquisition and subsequent model loading continue
- **AND** the TTS model readiness field shows Loading until both acquisition and model loading complete

#### Scenario: TTS model becomes ready
- **WHEN** every required Supertonic ONNX session, configuration, text resource, and voice resource loads successfully on the background thread
- **THEN** the system atomically marks TTS model readiness as ready
- **AND** subsequent TTS requests can be synthesized without reloading the model

#### Scenario: TTS model load fails
- **WHEN** Supertonic model loading fails on the background thread
- **THEN** the system marks TTS model readiness as failed
- **AND** the connected monitor/test surface shows the model load error instead of silently ignoring TTS requests
- **AND** every partially constructed ONNX session and value is closed

#### Scenario: TTS initialization failure leaves TTS paths unavailable without crashing
- **WHEN** the ONNX Runtime environment or Kotlin Supertonic engine cannot be constructed before a pollable engine is available
- **THEN** the system logs the failure without crashing the foreground service
- **AND** the TTS controller is not constructed
- **AND** the TTS model readiness poller is not started
- **AND** the connected monitor/test surface continues to show Loading for the TTS model readiness field
- **AND** the other test modes that do not require the Supertonic engine remain available

### Requirement: TTS test control is available in Debug Channel configuration
The system SHALL show the text-to-speech test mode option in the Debug Channel configuration screen.

#### Scenario: Debug Channel config surface is displayed
- **WHEN** the user opens the Debug Channel configuration screen
- **THEN** the system shows the TTS test mode as a selectable option among the debug modes
- **AND** if TTS mode is selected, below the selection the system shows an editable text box prefilled with a short default phrase
- **AND** below the TTS selection the system shows the Supertonic controls the integration exposes (voice style, language, quality/total steps, and speed)

#### Scenario: TTS test is not yet run
- **WHEN** the Debug Channel configuration screen is displayed before any TTS result exists and TTS is the active mode
- **THEN** the TTS status area shows an idle TTS state

### Requirement: TTS test synthesizes and plays text on demand
The system SHALL synthesize speech from the text in the TTS test text box using Supertonic with the user-selected parameters and play the resulting audio through the connected Bluetooth SCO route.

#### Scenario: User triggers TTS synthesis while enabled
- **WHEN** TTS test mode is enabled, the headset SCO route is available, and the user requests synthesis (for example by pressing PTT or tapping a synthesize control)
- **THEN** the system acquires the Bluetooth SCO route
- **THEN** the system synthesizes speech from the current text box content with the selected voice style, language, total steps, and speed
- **AND** the system plays the synthesized audio through the headset SCO route
- **AND** the system shows a synthesizing status while synthesis is in progress and a playback status while audio is playing

#### Scenario: Synthesis completes successfully
- **WHEN** Supertonic returns audio for the requested text
- **THEN** the system resamples the 44.1 kHz output to the SCO output rate
- **AND** the system plays the resampled audio through the headset
- **AND** the system returns to an idle TTS status after playback completes

#### Scenario: Text box is empty
- **WHEN** TTS test mode is enabled and the user requests synthesis while the text box is empty
- **THEN** the system does not invoke Supertonic
- **AND** the system shows an empty-text TTS status

#### Scenario: Model is still loading when synthesis is requested
- **WHEN** TTS test mode requests synthesis while Supertonic model loading is still in progress
- **THEN** the system waits for model readiness without blocking the UI thread
- **AND** the connected monitor/test surface shows a waiting-for-model or synthesizing status

#### Scenario: Synthesis fails
- **WHEN** Supertonic synthesis returns an error
- **THEN** the system stores a TTS error status
- **AND** the connected monitor/test surface shows the error instead of playing audio

#### Scenario: Audio would leave the device
- **WHEN** TTS test mode processes text or audio
- **THEN** the system MUST NOT send the text, transcript, or synthesized audio to a network service

### Requirement: TTS test exposes Supertonic-controllable parameters
The system SHALL expose the Supertonic parameters that the integration supports below the TTS test toggle and use them when synthesizing.

#### Scenario: User changes a Supertonic parameter
- **WHEN** the user changes the voice style, language, total steps, or speed control below the TTS test toggle
- **THEN** the system stores the new parameter value in app state
- **AND** the next TTS synthesis uses the updated parameter value

#### Scenario: Invalid language is rejected
- **WHEN** the user selects a language Supertonic does not support
- **THEN** the system does not attempt synthesis
- **AND** the system shows a TTS error status describing the invalid language

### Requirement: TTS sessions are cancelled on disconnect or mode change
The system SHALL stop active or pending TTS synthesis or playback work when the relevant device session or active test mode ends.

#### Scenario: Serial disconnect occurs during TTS work
- **WHEN** TTS test mode has active or pending synthesis or playback work and the serial connection is disconnected
- **THEN** the system cancels active TTS work
- **AND** the system releases any acquired SCO route
- **AND** the system leaves no TTS work active after disconnect completes

#### Scenario: TTS mode is disabled during playback
- **WHEN** TTS test mode is playing audio and the user disables TTS test mode
- **THEN** the system stops playback
- **AND** the system releases any acquired SCO route

#### Scenario: Another mode is enabled during TTS work
- **WHEN** TTS test mode has active or pending work and the user enables echo, STT, or STT↔TTS test mode
- **THEN** the system cancels TTS work
- **AND** the system enables the newly selected mode

### Requirement: TTS synthesis request does not perform asset file I/O on the main thread
The system SHALL resolve the Supertonic voice style file path from the model directory captured during the background initialization, without re-invoking the asset extractor on the main thread on each synthesis request.

#### Scenario: Synthesis request reuses cached model directory
- **WHEN** the user requests TTS synthesis (by tapping the synthesize control or pressing PTT) and the Supertonic model has finished initializing
- **THEN** the system resolves the voice style file path from the model directory stored during the background initialization
- **AND** the system does not read the asset version marker file on the main thread
- **AND** the system does not perform any asset extraction file I/O on the main thread

#### Scenario: Synthesis request before initialization completes
- **WHEN** the user requests TTS synthesis before the Supertonic model has finished initializing
- **THEN** the system ignores the request without performing asset file I/O on the main thread
- **AND** the TTS controller remains unavailable until the background initialization completes

