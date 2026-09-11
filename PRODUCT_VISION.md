# Talkcan Product Vision

## Product Thesis

Talkcan is a radio of channels for voice tools.

The product exists to provide the most ergonomic possible interface between the user's voice and a configurable set of tools. Those tools may be AI agents, third-party services, local automations, recording workflows, webhooks, or other user-defined destinations. AI is an important possible use case, but it is not the definition of the product.

Talkcan treats voice as the primary input format and channels as the primary routing model. The user should be able to choose where their voice goes, speak with minimal friction, hear responses when they arrive, and move between conversations without touching or looking at the phone whenever possible.

The initial primary interface is the `B02PTT-FF01` Bluetooth PTT device because it is physically designed for this interaction model: a strong microphone, a strong speaker, a hold-to-talk button, and enough secondary buttons to multiplex one voice path across multiple channels.

## Core Metaphor

Talkcan keeps the simplicity of a tin-can telephone and replaces the single string with configurable channels.

- **The can** is the user's microphone, speaker, and push-to-talk control.
- **The string** is the selected channel and its routing pipeline.
- **The other end** is the destination: a person, log, service, automation, webhook, or assistant.
- **The knot** is configuration: the rules that bind voice to an outcome.

The product should feel like a programmable radio, not like a conventional chat app. The user does not primarily type into text boxes, browse nested screens, or manually choose an action for every utterance. Instead, the user selects a channel, holds PTT, speaks, releases, and lets the configured channel decide what happens.

Each channel is a conversational route. A channel can record, store, transcribe, forward, transform, automate, or respond. The same hardware interaction can therefore drive very different behaviors depending on the active channel.

## Product Goals

- Make voice capture and routing faster than opening an app and tapping through menus.
- Make channel switching possible without looking at the screen.
- Make asynchronous voice conversations feel natural.
- Let users configure channels at a high level first, with advanced low-level control available later.
- Keep hardware operation deterministic, predictable, and easy to learn.
- Keep the visual interface focused on monitoring, configuration, and review, not as the primary live control surface.

## Primary User Experience

The default experience is:

1. The user has a set of configured channels.
2. One channel is active.
3. Holding PTT records a complete audio message and sends it to the active channel.
4. The channel may produce no response, one response, or many responses.
5. Responses from the active channel play automatically when the user is not transmitting.
6. Responses from inactive channels are kept for later.
7. The user can switch channels using hardware controls.
8. The user can replay the last heard message or inspect channel history using hardware controls.

Talkcan is not request-response. The user can send multiple messages in a row. The other end of a channel can respond later, send multiple messages, or send messages while the channel is inactive.

## Channels

A channel defines what happens when the user speaks into it.

At a product level, a channel has:

- A user-visible name.
- A position in the user's channel list.
- A channel type or integration.
- Channel-specific configuration.
- A conversation history.
- An inbound message stream.
- An outbound message stream.
- A pending unheard message state.
- A last-heard message state.

Channels are heterogeneous. They do not all need to be AI agents and they do not all need to produce responses.

Example channel behaviors:

- Store each spoken message as chronological audio.
- Transcribe spoken messages and store chronological text.
- Send audio to an external service.
- Transcribe audio and send text to an external service.
- Trigger a webhook with audio, text, metadata, or a combination of them.
- Talk to an AI assistant.
- Talk to a local automation.
- Talk to a third-party application or service integration.

## Channel Configuration Philosophy

Talkcan should expose channel configuration in two layers.

The normal layer is high-level. Users choose a channel type, connect accounts or endpoints if needed, and configure a small set of meaningful parameters.

The advanced layer unlocks lower-level pipeline control. This can allow users to compose capture, transcription, transformation, routing, storage, webhook, and response steps more freely.

The high-level layer is the default product experience. The low-level layer is for users who need automation power and accept the additional complexity.

## Messages

A message is a discrete conversational unit inside a channel.

A user-originated message is one complete PTT capture, from press to release. It is always captured as audio from the user's point of view. The concrete storage or transfer format is intentionally not defined here. It may later be an audio file such as Ogg, WebM, or another format.

A remote-originated or system-originated message is a playable message produced by the other side of the channel. It may originate as audio, generated speech, transformed text, a service response, an automation result, or another source. From the user's operational point of view, it must be playable.

Messages form a channel history similar to a chat history, but audio is the primary primitive.

Talkcan distinguishes these terms:

- `latest message`: the most recent chronological message in a channel.
- `last heard message`: the most recent message that was actually played to the user in that channel.
- `pending unheard message`: a message received by a channel that has not yet been played or consumed for autoplay.

## Asynchronous Conversation Model

Each channel behaves conceptually like two asynchronous message streams:

- outbound: audio messages sent by the user to the channel.
- inbound: messages produced by the other side of the channel.

The implementation does not need to literally expose these as two queues, but the product model must support queue-like behavior: asynchronous delivery, backlog, replay, unread state, ordering, per-channel history, and delayed playback.

Inactive channels may receive messages. Those messages are not played immediately by default. They become pending unheard messages for that channel.

## Active Channel Behavior

The active channel is the destination for normal PTT transmission.

When the user holds PTT in active mode:

- Talkcan captures audio.
- The capture becomes one outbound user message.
- The message is sent to the active channel.
- The message is added to that channel's history.

When an inbound message arrives on the active channel and the user is not transmitting:

- Talkcan plays it automatically.
- The message becomes the last heard message for that channel.

When an inbound message arrives on the active channel while the user is transmitting:

- Talkcan queues it.
- Talkcan does not interrupt or mix playback into the user's transmission.
- After PTT release, Talkcan automatically plays the queued active-channel messages.

This defines half-duplex channels from the user's operational perspective.
For a full-duplex channel, clicking Talk starts continuous two-way audio.
Clicking Talk again stops the conversation. Full-duplex channels use the same
channel list and operational control, not a separate assistant interface.

## Inactive Channel Behavior

When an inbound message arrives on an inactive channel:

- Talkcan keeps it as pending unheard backlog for that channel.
- It does not automatically interrupt the active channel.
- The channel list should be able to show that the channel has pending messages.

When the user later activates a channel with pending unheard messages:

- Talkcan announces the selected channel.
- The announcement includes the channel name and the count of pending unheard messages.
- Talkcan then automatically plays the pending backlog.

Example announcement:

```text
Assistant. 3 messages waiting.
```

## Playback and Skip Behavior

Talkcan should keep audio playback controllable with the hardware.

In active mode, a short SOS press has two duties depending on playback state:

- If no audio is playing, it replays the last heard message for the active channel.
- If audio is playing, it stops the current message and advances to the next queued or unheard message if one exists.

When the user skips a currently playing message with short SOS:

- The skipped message is marked as heard or consumed for autoplay purposes.
- It does not auto-play again as pending backlog.
- It remains available in the channel history.

This makes short SOS function as both replay and next, depending on context.

## Priority Channel

The priority channel is a normal channel marked by the user for immediate access.

The current hardware exposes this through the long SOS hold interaction. Despite the name inherited from the hardware, this does not have to represent a real emergency channel. In many configurations it will likely be the user's default assistant channel.

In active mode:

- A long SOS press addresses the user-selected priority channel without changing the regular channel selection.
- SOS ends any running regular full-duplex conversation before priority communication starts.
- For half-duplex priority channels, capture continues while SOS remains held. Release sends the recording.
- For full-duplex priority channels, the conversation continues while SOS remains held. Release stops it.
- The regular conversation does not resume after release.

Normal Talk and SOS have independent channel selections. The priority selection
contains one configured channel and takes precedence while SOS is held.

## Hardware-First Interaction Model

The first target hardware is the `B02PTT-FF01` Bluetooth PTT device.

The product should treat the hardware as an ergonomic interaction surface, not merely as a peripheral. Its physical controls define the first-class operating model for the early product.

The device has two relevant modes:

- Active mode.
- Control mode.

Active mode is the normal speaking mode. Control mode is the navigation mode.

## Active Mode Controls

In active mode:

- Talk holds record and send on half-duplex channels. Talk clicks start or stop full-duplex conversations.
- SOS short replays the last heard message when no audio is playing.
- SOS short skips the current message when audio is playing.
- SOS long hold talks to the priority channel using its duplex mode. Release finishes that interaction.
- Control button enters control mode.

Active mode is optimized for talking and listening with minimal cognitive overhead.

## Control Mode Controls

Control mode turns the hardware into a channel and menu navigator.

In control mode:

- Volume Up and Volume Down move a cursor in the current control menu.
- PTT short confirms the current selection and returns to active mode.
- The Control button moves to the next control menu.
- SOS short enters the conversation history for the active channel.

The first and default control menu is always the channel menu. In that menu:

- Volume Up and Volume Down move a channel cursor.
- The active channel does not change while moving the cursor.
- PTT short confirms the selected channel.
- After confirmation, Talkcan enters active mode, announces the selected channel, and auto-plays pending backlog if any exists.

Additional Control button presses may cycle through other menus. The product may add more menus over time, but the channel menu remains the primary and default control menu.

## Channel History Mode

Channel history mode is entered from control mode with SOS short.

The history is scoped to the active channel.

In channel history mode:

- Volume Up and Volume Down navigate through messages in the active channel history.
- Moving to a message automatically plays the selected message.
- PTT short exits directly to active mode.
- PTT short in history mode does not record or send audio.

The goal is to let the user review a conversation without needing the touchscreen.

## Visual Interface Role

The phone screen is a status monitor, configuration surface, and review surface.

It should not be required for the core live loop of selecting a channel, talking, hearing replies, replaying the last message, or entering history. Those operations should be possible from hardware controls once the system is configured.

The main screen should show:

- The active channel.
- Connection and readiness state for the current input/audio surface.
- Channel list and pending message indicators.
- Current playback or transmission state.
- Channel configuration access.
- Conversation history access.

The visual design should support glanceability. The user should be able to understand the operational state from arm's length.

## Channel Types

Talkcan should support built-in channel types and extensible integrations.

Initial or likely channel categories:

- Audio log channel: stores PTT captures as chronological audio.
- Transcription log channel: transcribes PTT captures and stores chronological text.
- External audio channel: sends audio to a configured service.
- External text channel: transcribes audio and sends text to a configured service.
- Webhook channel: sends configured payloads to a user-defined endpoint.
- Assistant channel: sends messages to an AI assistant or agent and plays responses.
- Integration channel: connects to a third-party application or service.
- Advanced pipeline channel: exposes lower-level configurable processing steps.

The product should avoid implying that every channel is conversational, synchronous, or AI-backed. A channel is simply a voice-addressable route with history and optional responses.

## Platform Evolution

The initial product centers on the `B02PTT-FF01` device.

Future surfaces may include:

- Other physical PTT devices with similar controls.
- A hardware-free Android mode using on-screen controls.
- Android Auto for in-car operation.
- Other input surfaces that can produce equivalent talk, navigation, replay, and priority-channel intents.

The product model should remain independent from any single hardware implementation. Hardware-specific code should translate physical events into product-level intents such as talk to active channel, enter control mode, move cursor, confirm selection, replay or skip, enter history, and talk to priority channel.

## Non-Goals For The Vision

This document does not define:

- Concrete audio container formats.
- Concrete transcription providers.
- Concrete AI providers.
- Concrete privacy, retention, or encryption policies.
- Concrete data schemas.
- Concrete network protocols.
- Concrete integration APIs.
- Concrete UI layouts.
- Android implementation architecture.

These areas matter, but they should be specified separately. This document defines the long-term product model and interaction behavior.

## Product Principles

- Voice first: the primary user action is speaking, not typing.
- Hardware first: early iterations optimize for the physical PTT device.
- Channels first: channels are the main organizing unit.
- Audio is primary: user-originated messages are captured as audio.
- Asynchronous by default: channels can receive messages while inactive.
- No forced request-response: users and channels can send multiple messages independently.
- Eyes-free operation: core live controls should work without the screen.
- Predictable controls: the same button should have a small number of state-dependent meanings.
- User-configurable routing: the user decides what each channel does.
- High-level first, advanced later: simple channel setup should come before low-level pipeline composition.
- Visuals support operation: the UI should clarify state, not compete with the hardware control model.

## Guiding Summary

Talkcan is a hardware-first, voice-first channel router.

It lets the user speak into configurable channels as if operating a radio, while each channel can represent a different tool, workflow, service, assistant, automation, or log. The `B02PTT-FF01` provides the first ergonomic control surface: PTT for the active channel, SOS long-hold for the priority channel, short SOS for replay or skip, and control mode for channel and history navigation.

The long-term product should preserve that radio-like immediacy even as channels become more powerful, configurable, and diverse.
