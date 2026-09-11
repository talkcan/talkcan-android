# Talkcan

**A programmable walkie-talkie for your tools.**

Talkcan is a hardware-first, voice-first channel router. Choose a channel, hold push-to-talk, speak, and let that channel decide what happens: record it, transcribe it, trigger an automation, call a webhook, send it to a service, or play a response.

The core idea is as simple as a tin-can telephone. The can is your microphone, speaker, and talk button. The string is the selected channel and its route. Something useful is on the other end. Talkcan keeps that simplicity and replaces the single string with configurable channels — one voice interface connected to many destinations.

## Project status

Talkcan is in active development. There is **no public release yet**: no installable build, no app-store listing, and no published packages. This repository currently holds the project vision and brand foundation; the Android client code is being migrated in and will land in later steps. Nothing here should be read as a claim that Talkcan is available to install or use today.

See <https://github.com/talkcan/talkcan-android>.

## Vision documents

- [`PRODUCT_VISION.md`](PRODUCT_VISION.md) — the product model, interaction behavior, and hardware-first channel-routing vision.
- [`VISUAL_IDENTITY.md`](VISUAL_IDENTITY.md) — brand identity, color palette, typography, and UI/UX principles.
- [`PLUGIN_SYSTEM_VISION.md`](PLUGIN_SYSTEM_VISION.md) — the intended shape of the Talkcan channel plugin ecosystem.

Talkcan is **friendly on the outside; deterministic underneath.**

## GPT-Live conversations

GPT-Live is a full-duplex channel. It sends microphone audio to OpenAI while
it plays the assistant's voice. It uses `gpt-live-1` at
`wss://api.openai.com/v1/live/sessions`, not the Realtime API.
The [GPT-Live guide](https://developers.openai.com/api/docs/guides/live)
describes the voice session and its separate backend model.

1. In **Settings → Channel management**, add a **GPT-Live** channel.
2. In **Settings → GPT-Live**, enter your OpenAI API key.
3. Select the channel under **SOS live channel**.
4. Tap **Save**.
5. Press SOS, or tap **Start live** on the Radio screen.

Press SOS again, or use **End live** on the screen or notification, to stop.
The microphone stays active until the conversation ends. Starting the
conversation does not change the regular PTT channel. Regular PTT and other
audio playback cannot run during the conversation.

The channel settings select the voice, backend model, instructions, and tool
permissions. Channel switching and file reads are disabled by default.
The tools can list channels, inspect channel status, select a channel, list
mounted files, and read bounded UTF-8 text. File reads use each channel's
declared folder grants. Tools cannot read host credentials, transmit PTT
recordings, delete channels, or change app settings.

The app encrypts your API key with Android Keystore. The key authenticates
requests to OpenAI. Audio and permitted tool results leave the device.
OpenAI bills voice sessions and backend work separately.

Local socket tests cover simultaneous audio, delegated tools, duplicate
calls, overload, startup cancellation, and shutdown. An authenticated
GPT-Live session and physical headset audio still require device verification.
