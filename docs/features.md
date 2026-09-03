# Features

## Main screen

Laid out around one claim: the button is the screen. The readout sits at the top where it is read
but not touched; the channel stepper and the PTT button are grouped in the bottom half, within
thumb reach. Rationale and the full state table: [`ui-design.md`](ui-design.md).

- **Status card** — a status dot plus the state in words: `Offline`, `Connecting…`,
  `Channel clear`, `Requesting…`, `You are on air`, or `<name> is talking`. The second line
  changes job with the state: connected it counts the radios listening, offline it shows the
  address the app is failing to reach — the whole first-run debugging story for a self-hosted
  relay.
- **Connect / Disconnect** — in the status card. Until now the session started as a side effect of
  granting the microphone permission and could not be stopped from the app at all.
- **Channel selector** — a −/+ pill over channels 1..99. The ends disable rather than silently
  doing nothing, and the control is disabled while transmitting (switching mid-transmission would
  strand the floor). Changing channel persists it and reconnects.
- **PTT button** — hold to talk. Solid in the status colour when it is live; hollow, keeping the
  status colour as a ring, when the *channel* is why you cannot press; flat grey only when the
  microphone permission is missing. Pulses while transmitting **and** while receiving. A haptic
  confirms the floor grant — the moment it is actually safe to speak — and a lighter one the
  release. TalkBack gets a toggle action, since a screen reader cannot express press-and-hold.
- **Reason line** — when the button is unusable it says why: microphone permission needed, not
  connected, connecting, someone else talking, or waiting for the floor. The missing-permission
  case, the only one with a remedy, also gets a **Grant** button.
- **Error banner** — the last transport or protocol error, dismissible. It used to be a line of
  small red text under the button with no way to clear it, so a refusal from twenty minutes ago
  sat there looking like a live fault.
- **Landscape and tablets** — the readout moves beside the button rather than above it, and
  neither column stretches past 520.dp.

## Settings

Grouped into cards behind the gear icon, with **Save and reconnect** pinned to a bottom bar so it
stays reachable with the keyboard up. Saving is one atomic write, then a reconnect, confirmed with
a snackbar.

- **Relay** — **Default** or **Custom**. Default dials the address the app ships with and shows
  no field at all; most people never have a reason to change it, and it used to be two boxes at
  the top of the form. Custom reveals one address box that takes whatever is on the clipboard: a
  bare host, a `host:port`, or a whole URL pasted from the server log or a tunnel — a scheme that
  is spelled out sets the port (443 for `https://`) and turns encryption on. Either mode shows a
  live preview of the exact `ws://…` URL it will dial, which is what makes inferring a port from a
  pasted scheme safe: the inference is on screen before it can be saved.
- **Identity** — display name, up to 32 characters, shown to peers as the floor holder.
- **Channel** — 1..99.
- **Appearance** — System / Light / Dark. The app follows the system by default, but a radio gets
  pulled out at night on a phone whose owner has never touched the system theme, so it can be
  forced per-app.
- **Language** — System / English / Russian / Serbian. This is a walkie-talkie: it gets handed to
  someone who does not read whatever language the phone owner set up — a guest, a kid, a coworker
  borrowing a channel for the afternoon — so the UI language can be forced independently of the
  device's own locale. A system locale that is none of the three falls back to English. On
  Android and desktop the change takes effect immediately; on iOS, best-effort and
  runtime-unverified, it is expected to take effect on the next launch rather than the current
  one — see `platform-support.md`.
- **Hands-free** — the floating PTT button toggle, with a hand-off to the system "draw over other
  apps" screen when the permission is missing, and **Host a relay on this device**, which runs the
  server in-app so no separate machine is needed.

## Security

Three settings under **Settings → Security**, all optional, all matching something the relay was
started with:

- **Encrypted connection** — `wss://` instead of `ws://`. The URL preview above updates as you
  toggle it, so the setting is never ambiguous.
- **Certificate fingerprint** — for a relay serving its own self-signed certificate, which is what
  `ptt-server` generates on first boot. Paste the SHA-256 it prints; colons and case do not
  matter. Leave it empty when the relay has a publicly trusted certificate, such as through a
  tunnel.
- **Access token** — the relay's shared secret, sent as a header. Masked, with a Show toggle.

A half-typed fingerprint blocks Save rather than being accepted and quietly matching nothing. A
fingerprint left over from a previous relay is not applied to an unencrypted connection, where it
would imply protection that is not there.

Turning on encryption while **Host a relay on this device** is also on is called out inline: the
on-device relay speaks plaintext only, so that pair can never connect.

When a handshake fails, the banner says why — "Certificate fingerprint does not match. Expected
…, got …" — rather than surfacing the `SSLHandshakeException` that wrapped it.

## Talk floor

The server allows one talker per channel. Pressing PTT requests the floor and transmission begins
only once the server grants it, so two people pressing simultaneously cannot both be heard. While
someone else holds it, every other client's PTT control is disabled and shows who is speaking.

## Talking without opening the app

Three surfaces, all driven by the same foreground service:

| Surface | Gesture | Notes |
|---|---|---|
| **Notification** | Tap **Talk** / **Stop** | Always present while the session runs; also shows channel and status, plus **Disconnect** |
| **Floating bubble** | **Hold** to talk, drag to move | Real press-and-hold, and the only surface that shows the channel while another app is in front. Position persists. Hidden while the app itself is on screen, where it would only cover the button it duplicates |
| **Home-screen widget** | Tap to toggle transmit; −/+ for channel | Tap-to-toggle only — widgets cannot receive touch-down/up. The container follows the launcher's theme; only the transmit key is painted from the shared status palette |

### The floating bubble

A 100.dp disc carrying the three things you need while another app is in front:

- **The channel number**, zero-padded. Nothing else on screen tells you which channel a press
  would go out on.
- **A microphone**, struck through whenever a press could not start a transmission — so the bubble
  is not relying on colour alone to say "not now".
- **The state word** (`HOLD` / `ON AIR` / `BUSY` / `WAIT` / `LINKING` / `OFFLINE`) and the state
  colour, both the same as the app screen's.

Press anywhere on it to talk; drag past the touch slop to move it instead, which cancels the
transmission it had begun. It is drawn from primitives rather than loaded drawables — it is
inflated against a service `Context` where a vector's `?attr/…` tint has no guaranteed theme, and
a throw there takes the overlay window with it.

## On-device relay

With **Host a relay on this device** enabled, the app runs the same protocol-v1 relay internally
(`internalserver/InternalPttServer.kt`), including per-channel isolation and floor control. Other
phones on the same Wi-Fi set **Relay → Custom** to this device's LAN address; this device sets its
own to `127.0.0.1`. No separate server needed.

## Not included

- No accounts — the access token is one shared secret for the whole channel, with no per-handset
  revocation.
- No audio compression; raw 16 kHz mono PCM (~32 kB/s). Fine on a LAN, wasteful over the internet.
- The on-device relay serves plaintext only. Encryption means pointing at `ptt-server`.
- No message history, no text chat, no per-user mute.

## Related

- [`ui-design.md`](ui-design.md) — why the interface looks the way it does, and the state table
