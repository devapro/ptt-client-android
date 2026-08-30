# UI design

## What the interface is for

A walkie-talkie is used with one hand, in motion, while doing something else. The user is
usually **not looking at the screen** — they are looking at the road, the load, the other person.
That single fact decides almost everything below.

Three questions have to be answerable in one glance, from arm's length:

1. **Can I talk right now?**
2. **Is anyone hearing me?**
3. **Which channel am I on?**

Everything else — the address of the relay, the display name, the toggles — is setup, and setup
belongs behind a gear icon.

## Rules

**One saturated thing on screen, and it is the state of the channel.** The greys are a cool,
low-chroma ladder precisely so the status accent has nothing to compete with. If a second element
starts shouting, the readout stops working.

**Colour follows radio convention, not traffic lights.** Red is *on air* — you are audible — not
"stop". An incoming transmission is blue, not green, because green here means "the channel is
yours", which is the exact opposite of somebody else holding the floor. Getting this backwards is
how a user talks over someone.

**Colour is never the only signal.** Every state also changes the word on the face of the button
and the glyph above it. That is what makes the interface usable for a colour-blind user, in
sunlight, and in a screenshot.

**No dynamic colour.** Material You would derive `primary` from the wallpaper. Here colour is the
readout, not decoration, and the same five states have to look the same in the app, in the floating
bubble drawn on a raw `Canvas`, in the Glance widget and in the notification — none of which can
follow a wallpaper-derived scheme. See `ui/theme/Theme.kt`.

**Dark is the designed scheme; light is its mirror.** The app holds a foreground service open for
hours and gets looked at outdoors and at night. It follows the system by default and can be forced
to either scheme in Settings — a radio gets pulled out at night on a phone whose owner has never
touched the system theme.

**Every Material colour role is defined, not just the interesting ones.** Any role left out of
`darkColorScheme`/`lightColorScheme` silently falls back to Material's baseline purple, which is
how a lavender chip turned up inside a segmented control on an otherwise green-and-slate screen.

**Everything you touch is in the bottom half.** The button and the channel stepper are grouped
together within thumb reach; the status card, which is read and not touched, sits at the top. The
previous layout stacked everything from the top and left the reachable third of the phone empty.

**Say why a control is dead, and offer the fix.** When the button will not do anything, the line
underneath says which of the four reasons applies, and the one reason with a remedy — a missing
microphone permission — gets a button.

## The five states

One enum, `ui/PttUiStatus`, owns the mapping from `PttState` to colour and wording, and all four
surfaces read it. It is deliberately free of Compose and of Android so that it is unit-tested
(`PttUiStatusTest`) — if these drift, the floating bubble and the big button start disagreeing
about whether the channel is free, and the bubble is the only readout the user has while not
looking at the app.

| State | Colour | Face | Means |
|---|---|---|---|
| `OFFLINE` | slate | `OFFLINE` | No transport. Nothing here will do anything |
| `CONNECTING` | amber | `LINKING` | Socket in progress, including backoff retries |
| `READY` | green | `HOLD` | Connected, floor free — the only state where a press transmits |
| `REQUESTING` | amber | `WAIT` | `talk_request` sent, no answer yet. Speaking now clips your first word |
| `TRANSMITTING` | red | `ON AIR` | The server granted the floor; the microphone is open |
| `RECEIVING` | blue | `BUSY` | Someone else holds the floor. Hands off |

`REQUESTING` shares amber with `CONNECTING` on purpose: both mean "something is pending, wait".

## The button

It is the screen. It is also the primary readout, which is why its appearance has three modes
rather than two:

- **Live** — solid disc in the status colour, dark content on top. Pressable.
- **Dead because of the channel** (receiving, offline, connecting) — hollow: dark fill, a thick
  ring in the status colour, content in the status colour. Still a readout, visibly not a button.
- **Dead because of us** (no microphone permission) — flat grey. The fault is ours, not the
  channel's, so it does not borrow the channel's colour.

Other things it has to do that a plain `Button` does not:

- **Confirm on the grant, not the press.** The moment it becomes safe to speak is when the server
  hands over the floor, a beat after the press — so that is when the haptic fires. A lighter tick
  fires on release.
- **Survive state changing under a held finger.** The gesture is `awaitEachGesture` with the
  release in a `finally`, and the `pointerInput` is keyed on `Unit`. Anything else — keying on
  `enabled`, or hanging the modifier off it — tears the detector down mid-hold when the grant
  arrives, and the release half of the gesture is simply lost: floor held, microphone open,
  nobody else on the channel able to talk. See known-issues #20.
- **Be operable without the gesture.** TalkBack cannot express press-and-hold, so the semantics
  expose a plain toggle action alongside it.
- **Fit.** The diameter is computed from what is left after the readout rather than fixed, which
  is what makes landscape and small screens work.

## The floating bubble

The same five states, in 100.dp, over somebody else's app. It carries what the app screen carries
minus what it cannot fit: the channel number (nothing else tells you which channel a press goes
out on), a microphone glyph struck through when a press would do nothing, the state word, and the
state colour. A dark halo and a light rim, because it floats over arbitrary wallpapers and a flat
disc vanishes against anything of a similar tone.

It is sized in dp. It used to be a flat `200` raw pixels — a thumb-sized target on an mdpi tablet
and a pinhead on a 4x screen.

## Layout

```
portrait                          landscape
┌───────────────────────┐         ┌───────────────────────────────────┐
│ PTTdroid          ⚙   │         │ PTTdroid                      ⚙   │
│ ┌───────────────────┐ │         │ ┌─────────────────┐               │
│ │ ● Channel clear   │ │  read   │ │ ● Channel clear │      ╭─────╮  │
│ │   2 radios online │ │         │ │   2 radios      │     │ HOLD  │ │
│ └───────────────────┘ │         │ └─────────────────┘      ╰─────╯  │
│                       │         │      CHANNEL                      │
│        CHANNEL        │         │      ( − 01 + )    Hold to talk    │
│       ( − 01 + )      │  touch  └───────────────────────────────────┘
│        ╭───────╮      │
│       │  HOLD   │     │
│        ╰───────╯      │
│   Hold to talk        │
└───────────────────────┘
```

Neither column grows past `READOUT_MAX_WIDTH` (520.dp), and the settings form stops at
`FORM_MAX_WIDTH` (640.dp). On a tablet, a full-width status card is a metre of empty surface with
two words in the corner, and a full-width text field is the least readable measure there is.

## Related

- [`features.md`](features.md) — what the app does, from the user's side
- [`architecture.md`](architecture.md) — where the UI sits in the MVI loop
