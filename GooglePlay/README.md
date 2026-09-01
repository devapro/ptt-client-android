# Google Play store listing

Everything the Play Console asks for on the **Main store listing** and **Store settings** pages,
plus the script that regenerates it. Nothing here is compiled into the app.

```
GooglePlay/
  listing/                     the three text fields, one file each
  graphics/                    app icon and feature graphic
  screenshots/phone/           7 x 1080x1920
  screenshots/tablet-7/        3 x 1920x1080
  screenshots/tablet-10/       3 x 2560x1440
  raw/                         the unretouched device captures the above are built from
  build/make_assets.py         regenerates every image, then checks it against Play's rules
```

## Regenerating

```bash
python3 GooglePlay/build/make_assets.py               # everything, then verify
python3 GooglePlay/build/make_assets.py --only phone  # or tablet | graphics | verify
```

It needs `firefox` (the renderer — it is the only thing here that honours an exact
`--window-size`, which is what makes the canvases byte-exactly the size Play validates) and
`ffmpeg` (to flatten the alpha channel Play rejects). Both are already required to be on PATH;
no Python packages are needed. Captions, ordering and which capture feeds which slide are all
in `PHONE_SHOTS` / `TABLET_SHOTS` at the top of the script.

The last thing the script does is re-measure everything it produced:

```
verifying against Play's requirements
  all assets satisfy Play's size, ratio, format and count rules
  ok   listing/title.txt: 28/30 characters
  ok   listing/short_description.txt: 77/80 characters
  ok   listing/full_description.txt: 3226/4000 characters
```

## What Play requires, and what is here

| Asset | Play's rule | What is in this folder |
|---|---|---|
| Phone screenshots | 2–8, PNG/JPEG, 16:9 or 9:16, 320–3840 px per side, ≤ 8 MB | 7 at 1080×1920 (9:16) |
| 7-inch tablet | 2–8, 16:9 or 9:16, 1080–7680 px per side | 3 at 1920×1080 (16:9) |
| 10-inch tablet | as above | 3 at 2560×1440 (16:9) |
| Feature graphic | exactly 1024×500, no alpha | `graphics/feature-graphic-1024x500.png` |
| App icon | exactly 512×512, 32-bit PNG **with** alpha, ≤ 1 MB | `graphics/icon-512.png` |
| App name | ≤ 30 characters | `listing/title.txt` |
| Short description | ≤ 80 characters | `listing/short_description.txt` |
| Full description | ≤ 4000 characters | `listing/full_description.txt` |

Two rules are easy to trip over and are handled in the script rather than by eye:

- **The raw captures are not valid screenshots on their own.** A 1080×2400 handset capture is
  9:20, outside the 16:9–9:16 band Play accepts. Composing it onto a 9:16 canvas is what makes it
  uploadable, not merely what makes it look designed.
- **Play rejects screenshots carrying an alpha channel** and requires one on the icon. The
  verifier reads the colour type back out of each PNG's IHDR instead of trusting the ffmpeg flag.

## Where the pictures came from

Real captures from two emulators talking to each other over the relay in `relay.properties` —
one on channel 1 as "Sam", the other as "Dispatch" — so the BUSY slide really is one device
receiving the other, not a mock-up. Nothing in `raw/` is retouched; the compositing is only
canvas, caption and rounded corners.

Two details worth repeating if these are ever retaken:

- The status bars are SystemUI **demo mode**, which is why every shot reads 9:41, full Wi-Fi and
  100% battery with no notification clutter:
  ```bash
  adb shell settings put global sysui_demo_allowed 1
  adb shell am broadcast -a com.android.systemui.demo -e command enter
  adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
  adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
  adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
  adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
  adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
  adb shell am broadcast -a com.android.systemui.demo -e command exit    # afterwards
  ```
  Without `-e fully true` the Wi-Fi glyph carries a "no internet" mark, which is a poor look on a
  listing for a networking app.
- Hold the PTT control with `input motionevent DOWN` / `UP` rather than `input tap`. A tap is too
  short to be granted the floor, so it never produces the red ON AIR state.

## Caption colour is not decoration

Each slide's accent is the accent of the state its screenshot is showing — green when the channel
is free, red on air, blue when somebody else holds the floor, slate when the shot shows no
talk-floor state at all. The values are copied from `ui/PttUiStatus`. This is the same constraint
`docs/index.html` is under: a colour that means "on air" in the app cannot pick up a second,
decorative meaning in the marketing around it.

## Two copies of the store text, on purpose

`metadata/com.github.devapro.pttdroid.yml` says the user-facing text lives only under
`fastlane/metadata/android/en-US/`, so there is one copy rather than two that drift. **This folder
is a deliberate exception, and it is a real fork that has to be maintained by hand.** The two
stores want genuinely different things:

| | fastlane (F-Droid) | GooglePlay (Play) |
|---|---|---|
| Title | `PTTdroid` | `PTTdroid: Push-to-Talk Radio` — Play allows 30 characters and ranks on them |
| Screenshots | unframed device captures, which is what F-Droid renders | composed 9:16 canvases with captions, which is what Play expects |
| Full description | `*` bullets | `•` bullets, Play's narrower HTML subset |

Both descriptions say the same things about the app. **When one changes, change the other**, and
keep `fastlane/` authoritative for anything factual — the release workflow copies it into the
F-Droid index, and nothing copies this folder anywhere.

`fastlane/metadata/android/en-US/images/` still holds the older captures, including one at half
resolution; `raw/` here is the newer set if those are ever refreshed.

## Still needed before this can be published

None of these are assets, so none of them are in this folder — but Play will not take the listing
without them:

- [x] **A privacy policy at a public URL.** Written, at [`docs/privacy.html`](../docs/privacy.html),
      published by the Pages workflow to
      <https://devapro.github.io/ptt-client-android/privacy.html> — that is the URL to paste into
      **Store settings → Privacy policy**. It goes live on the next push to `main` that touches
      `docs/`; check it resolves before submitting, because Play rejects a listing whose policy
      URL 404s.
- [ ] **The Data safety form**, which must agree with that policy. The short version: no data
      collected or shared by the developer; audio and display name are transmitted to the relay
      the user configures. Audio qualifies for Play's *ephemeral processing* exemption — it is
      relayed live and never written to disk — but the display name travels in the connection URL
      and does appear in a relay's ordinary logs, so do not declare it as never leaving the
      device.
- [ ] **A declaration for the microphone foreground service.** The manifest declares
      `foregroundServiceType="microphone"`, which Play requires be justified in the console, with
      a video showing the feature in use.
- [ ] **A `SYSTEM_ALERT_WINDOW` justification**, for the floating button.
- [ ] **An Android App Bundle**, not the APK F-Droid takes: `./gradlew :app:bundleRelease` with
      the signing properties set. See [`docs/fdroid.md`](../docs/fdroid.md) for the keystore
      variables — but note that Play uses its own app-signing key, so this is a separate identity
      from the F-Droid one and the two builds are not interchangeable.
- [ ] **A content rating questionnaire** and a category (Communication).
