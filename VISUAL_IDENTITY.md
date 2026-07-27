# Talkcan Visual Identity

## 1. Brand Identity & Philosophy

**Concept:** A tin-can telephone for a flexible digital system.

Talkcan is friendly on the outside and deterministic underneath. The visual
identity is built from a familiar, slightly nostalgic metaphor — two cans
joined by a string — and carries it into a configurable, multi-channel voice
router. It is handmade in the best sense: tactile, approachable, and built to
do real work, not a glossy consumer toy and not a sci-fi terminal.

The brand can look playful because the product behavior must feel dependable.
The interface is designed to act as a base-station monitor for a handheld PTT
device, prioritizing instant glanceability, calm state communication, and clear
contrast. The interaction should remain simple even as the system behind it
becomes powerful.

### The metaphor in the identity

- **The can** is the user's microphone, speaker, and push-to-talk control.
- **The string** is the selected channel and its routing pipeline.
- **The other end** is the destination: a person, log, service, automation,
  webhook, or assistant.
- **The knot** is configuration: the rules that bind voice to an outcome.

The logo, palette, and graphic language all derive from strings, knots, nodes,
cans, and channels.

## 2. Product Alignment

The product is hardware-first and voice-first. The phone screen supports the
live loop; it does not replace it. The UI should make the active channel,
connection state, transmission state, playback state, and pending message state
obvious from arm's length.

Channels are routes for voice tools. A channel may be an assistant, audio log,
transcription log, webhook, automation, third-party integration, or advanced
pipeline. The visual system must not imply that every channel is AI-backed,
synchronous, or request-response.

## 3. Color Palette

Talkcan uses a warm utility palette: familiar materials, clear signals, and
enough contrast for real operational interfaces. The palette should feel tactile
and slightly nostalgic, but interfaces must remain crisp. Use the warm neutral
backgrounds generously; reserve the bright colors for signal, hierarchy, and
delight.

**Warm-light is the canonical, default identity.** A dark variant exists as a
derived surface for in-car and low-light use.

### 3.1 Warm-light (canonical / default)

| Token | Hex | Role |
|---|---|---|
| Ink | `#16181D` | Primary text and dark surfaces |
| Paper | `#F7F3E8` | Warm background |
| Can Red | `#F25F5C` | Brand accent |
| String Yellow | `#F6C945` | Attention and route |
| Radio Blue | `#2256A8` | Actions, links, and selected state |
| Tin | `#B9C0C8` | Borders and disabled |
| White | `#FFFFFF` | Cards and reverse |
| Signal Green | `#2D7A55` | Success and ready |

- **Background:** Paper `#F7F3E8`, the warm background. White `#FFFFFF` for
  raised cards and reverse surfaces.
- **Text:** Ink `#16181D` primary; Tin `#B9C0C8` for borders and disabled.
- **Accents:** Can Red for the brand, String Yellow for attention and routing,
  Radio Blue for actions/links/selected state, Signal Green for success and
  ready.

### 3.2 Dark variant (derived, in-car / low-light)

The dark variant keeps the same warm accents on a grounded dark background. It
is **not** a "night operations" or "deep space" theme and it carries no neon,
no cyan, and no sci-fi vocabulary. It is simply the warm-light identity laid on
Ink for low-light and in-car surfaces.

- **Background:** Ink `#16181D`, the grounded dark surface. Raised surfaces are
  derived from Ink and separated by Tin borders rather than brightened with
  unrelated hues.
- **Text:** Paper `#F7F3E8` and White `#FFFFFF`.
- **Accents:** Unchanged — Can Red, String Yellow, Radio Blue, Signal Green.
  Radio Blue and Signal Green remain legible against Ink; use Can Red primarily
  as a non-text accent on dark, pairing any red text with an icon or label.

### 3.3 Contrast and accessibility guidance

- **Ink on Paper:** 16.0:1 — suitable for all text sizes.
- **White on Radio Blue:** 7.1:1 — suitable for normal text and controls.
- **Ink on String Yellow:** 11.3:1 — suitable for notices and highlighted text.
- **White on Can Red:** 3.2:1 — do not use for small body text. Prefer Ink, or
  use red as a non-text accent.
- **Never communicate connection, recording, unread, or error state by color
  alone.** Every state must also carry a label, icon, or tonal cue.

## 4. Typography

Readable at a glance, friendly at display sizes, and practical across Android,
GitHub, documentation, and the web. Talkcan uses **Inter** as its single
primary family. Chakra Petch is not used.

| Role | Typeface | Use |
|---|---|---|
| Display | Inter Display ExtraBold | Wordmark-adjacent headlines and campaign statements |
| Interface | Inter Regular / Medium / Bold | UI, documentation, body copy, and labels |
| Code | Liberation Mono or system monospace | Commands, configuration, package names, and technical examples |

**Writing style for type:**

- Prefer sentence case. Avoid title case for every interface label.
- Keep operational labels short: "Ready," "Recording," "Sending," "Waiting."
- Use numerals for message counts and channel positions.
- Avoid excessive punctuation and all-caps except for short status labels.

## 5. Logo & Iconography

### 5.1 The mark

The Talkcan mark is built from the can, the string, and the branching channel
model. It begins with one familiar tin can. Its string branches into several
endpoints, representing the defining Talkcan behavior: one voice interface
connected to many configurable channels.

The wordmark is set lowercase as `talkcan` as a visual treatment. In prose the
brand is always written **Talkcan**.

### 5.2 Usage rules

**DO:**

- Keep the can upright enough to remain recognizable.
- Use approved palette colors or a single solid color.
- Preserve the relationship between symbol and wordmark.
- Use the full lockup whenever the audience may not recognize the symbol alone.
- Use the symbol alone for app icons, favicons, avatars, and compact hardware
  labels.
- Maintain clear space equal to the height of the can's top rim on every side.
- Do not place the full lockup below 32 mm wide in print or 160 px wide on
  screen.
- Use the monochrome mark when production constraints prevent the full palette.

**DON'T:**

- Do not add metallic gradients, bevels, or photorealistic texture.
- Do not replace the string endpoints with service logos inside the primary
  mark.
- Do not write the brand as "TalkCan".

### 5.3 Iconography style

- **Line-art based:** icons constructed from consistent stroke widths.
- **Rounded corners:** slightly rounded corners on icons and UI containers to
  keep the approachable feel, avoiding sharp, aggressive points.
- **State-driven:** icons communicate live state. The mic icon can pulse subtly
  while armed and pulse more urgently while transmitting.
- **Graphic devices:** borrow from strings (routes and dividers), knots and
  nodes (channel endpoints, queued messages, integration points), cans (anchors,
  empty states, onboarding), and stamped labels from practical equipment
  (numbered channels, compact status tags).
- **Illustration:** bold silhouettes legible at small sizes, flat shapes with a
  small amount of hand-drawn imperfection. No glossy 3D renders and no generic
  AI-generated futurism.

## 6. UI/UX Principles

Because the user interacts primarily via a physical handheld PTT device, the
phone screen acts as a status monitor.

- **Glanceability first:** from arm's length the user should instantly know
  connected state, active channel, transmission state, playback state, and
  pending message state.
- **State-driven UI:** the screen's accent treatment shifts based on live
  operational state. Because state is never communicated by color alone, each
  state also shows a label and an icon.
- **Calm:** avoid animated noise while recording, waiting, or playing messages.
  Motion should confirm an event, not decorate the idle surface.
- **Hardware-consistent:** on-screen controls reinforce the physical control
  model; they do not invent a competing one.

### State language

| Internal state | User-facing label | Meaning |
|---|---|---|
| Ready | Ready | The selected channel can accept a message |
| Capturing | Recording… | The button is held and audio is being captured |
| Routing | Sending… | The message is leaving the device |
| Queued | Saved for later | Delivery will resume when possible |
| Listening | Playing 2 of 3 | An inbound message is being heard |
| Unread | 3 waiting | The channel has pending messages |

### State accent mapping (warm-light)

- **Transmitting:** String Yellow / Can Red, with a large waveform confirming
  capture from the hardware PTT path.
- **Processing / waiting:** the waveform becomes a geometric loader or route
  indicator in Radio Blue.
- **Playback / channel response:** Radio Blue; the waveform animates as playable
  inbound audio or generated speech is heard.
- **Ready / success:** Signal Green.

In the dark variant these map to the same tokens on the Ink background.

### Channel metaphor on screen

The main screen displays large channel blocks such as Assistant, Audio Log,
Webhook, Automation, or Integration. The active channel is highlighted, pending
unheard messages are visible, and hardware navigation updates the UI
immediately.

## 7. Hardware Synergy

The UI complements a handheld push-to-talk radio.

- **Battery & signal indicators:** placed prominently at the top, styled like
  radio signal bars when the relevant data exists.
- **PTT visual confirmation:** when the physical PTT button is pressed, the app
  does not just start recording; it confirms the hardware input was registered
  with a clear state change and a capturing waveform.
- **Control mode feedback:** when the hardware enters control mode, the UI
  visibly distinguishes cursor movement from active-channel changes. The active
  channel changes only after confirmation.
- **History feedback:** channel history mode makes the selected historical
  message, playback state, and exit path clear without requiring touchscreen
  interaction.

## 8. Accessibility and Inclusion

The brand promise depends on usability without sight, precise touch, or
continuous attention.

- All core actions must have spoken or tonal confirmation that does not depend
  on seeing the screen.
- Do not rely on color alone for recording, playback, unread, error, or
  connection states — pair every color with an icon, label, or sound.
- Follow the contrast guidance in section 3.3. Prefer Ink on Paper and White on
  Radio Blue for text; treat Can Red as a non-text accent on small sizes.
- Support scalable type and preserve hierarchy at larger Android font settings.
- Use plain language and avoid humor in critical errors or destructive
  confirmations.
- Make channel names user-defined and announce them before pending-message
  counts.
- Provide transcripts when available, while keeping audio as a first-class
  message format.
- Design for one-handed and eyes-free use without assuming perfect hearing or
  speech.

Use inclusive language: "talk button" or "push-to-talk button," "couldn't
understand that recording," "connect an account or endpoint," "choose the
channel you want." Avoid "normal users" versus "advanced users," "you spoke
incorrectly," or gendered assumptions about assistants or voices.
