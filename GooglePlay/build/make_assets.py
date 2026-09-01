#!/usr/bin/env python3
"""
Builds every Google Play graphic asset in GooglePlay/ from the raw device
captures in GooglePlay/raw/.

Why a script and not a design file: the captures are real screenshots taken
with `adb exec-out screencap`, and the UI they show changes. Regenerating has
to be one command, or the store listing drifts away from the app the way the
old fastlane screenshots did.

Renderer: headless Firefox (`--screenshot`), which honours an exact
`--window-size` and so produces byte-exact canvas dimensions - the thing Play
actually validates. ffmpeg then flattens each PNG to 24-bit RGB, because Play
rejects screenshots carrying an alpha channel. The icon is the one asset that
keeps alpha (Play wants 32-bit there).

Play's asset rules encoded below, current as of 2026-08:
  phone screenshots  2-8, 16:9 or 9:16, each side 320-3840 px, <= 8 MB
  tablet screenshots 2-8 per size, 16:9 or 9:16, each side 1080-7680 px
  feature graphic    exactly 1024x500, no alpha
  app icon           exactly 512x512, 32-bit PNG with alpha, <= 1 MB

Usage:  python3 GooglePlay/build/make_assets.py [--only phone|tablet|graphics]
"""

from __future__ import annotations

import argparse
import base64
import html
import mimetypes
import shutil
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "raw"
BUILD = ROOT / "build"
SHOTS = ROOT / "screenshots"
GRAPHICS = ROOT / "graphics"

FIREFOX = shutil.which("firefox")
FFMPEG = shutil.which("ffmpeg")

# --- the palette -------------------------------------------------------------
#
# Lifted verbatim from shared/src/commonMain/.../ui/theme/Color.kt and
# ui/PttUiStatus.kt. A caption's accent is the accent of the state its
# screenshot is showing - green is "the channel is yours", red is "on air",
# blue is "someone else has the floor" - so the marketing page cannot teach a
# colour a second meaning. Neutral shots get SLATE, which means no state.

INK = "#0B0F14"
INK_TOP = "#10161F"
INK_BOTTOM = "#070A0E"
CHALK = "#E6EDF3"
CHALK_DIM = "#95A5B6"

GREEN = "#22C55E"  # PttUiStatus.READY
AMBER = "#F59E0B"  # CONNECTING / REQUESTING
RED = "#EF4444"  # TRANSMITTING
SKY = "#38BDF8"  # RECEIVING
SLATE = "#64748B"  # OFFLINE - and "this shot shows no talk-floor state"


@dataclass
class Shot:
    """One composed store screenshot."""

    out: str
    source: str
    accent: str
    eyebrow: str
    headline: str
    sub: str


@dataclass
class Canvas:
    """A Play screenshot size and the layout that fills it."""

    width: int
    height: int
    layout: str  # "portrait" (caption above) or "landscape" (caption beside)
    scale: float = 1.0
    shots: list[Shot] = field(default_factory=list)


# --- content -----------------------------------------------------------------

PHONE_SHOTS = [
    Shot(
        out="01-hold-to-talk.png",
        source="phone-ready.png",
        accent=GREEN,
        eyebrow="Channel clear",
        headline="Hold to talk.\nLet go to listen.",
        sub="A walkie-talkie for your group, over a relay you run yourself.",
    ),
    Shot(
        out="02-on-air.png",
        source="phone-onair.png",
        accent=RED,
        eyebrow="On air",
        headline="Red means\nyou're on air",
        sub="The relay grants the floor before the microphone ever opens.",
    ),
    Shot(
        out="03-one-talker.png",
        source="phone-busy.png",
        accent=SKY,
        eyebrow="Someone else is talking",
        headline="One talker\nat a time",
        sub="The channel is arbitrated, so nobody transmits into the same silence.",
    ),
    Shot(
        out="04-hands-free.png",
        source="phone-bubble-widget-onair.png",
        accent=RED,
        eyebrow="Hands-free",
        headline="Talk without\nopening the app",
        sub="A draggable floating button and a home-screen widget, always one press away.",
    ),
    Shot(
        out="07-background.png",
        source="phone-notification.png",
        accent=SLATE,
        eyebrow="Always connected",
        headline="The channel stays\nopen in the background",
        sub="A foreground service holds the session, with Talk and Disconnect in the notification.",
    ),
    Shot(
        out="05-your-own-relay.png",
        source="phone-settings-relay.png",
        accent=SLATE,
        eyebrow="No account",
        headline="Point it at\nyour own relay",
        sub="Encrypted over wss://, pinned to your relay's certificate, with a shared access token.",
    ),
    Shot(
        out="06-make-it-yours.png",
        source="phone-settings-handsfree.png",
        accent=SLATE,
        eyebrow="Yours to set up",
        headline="Even the relay\ncan be this phone",
        sub="Host the server on the device itself, and a group on one Wi-Fi needs no separate machine.",
    ),
]

TABLET_SHOTS = [
    Shot(
        out="01-tablet.png",
        source="tablet-ready.png",
        accent=GREEN,
        eyebrow="Channel clear",
        headline="Built for the\nbigger screen too",
        sub="The same channel, laid out wide - the talk button stays where a hand rests.",
    ),
    Shot(
        out="02-on-air.png",
        source="tablet-onair.png",
        accent=RED,
        eyebrow="On air",
        headline="Light or dark,\nit follows the system",
        sub="One colour language on every surface: the button, the widget, the notification.",
    ),
    Shot(
        out="03-who-has-the-floor.png",
        source="tablet-busy.png",
        accent=SKY,
        eyebrow="Someone else is talking",
        headline="You always know\nwho has the floor",
        sub="Each radio announces itself by name while it holds the channel.",
    ),
]

CANVASES = {
    # 1080x1920 is 9:16 exactly. The raw captures are 1080x2400 (9:20), which
    # is outside Play's accepted ratio range on its own - composing them onto a
    # 9:16 canvas is what makes them valid, not just what makes them pretty.
    "phone": Canvas(1080, 1920, "portrait", 1.0, PHONE_SHOTS),
    # 16:9, comfortably inside the 1080-7680 px per side that Play requires of
    # tablet screenshots.
    "tablet-10": Canvas(2560, 1440, "landscape", 1.0, TABLET_SHOTS),
    "tablet-7": Canvas(1920, 1080, "landscape", 0.75, TABLET_SHOTS),
}


# --- rendering ---------------------------------------------------------------


def data_uri(path: Path) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "image/png"
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode()


def png_size(path: Path) -> tuple[int, int]:
    head = path.open("rb").read(33)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    return struct.unpack(">II", head[16:24])


FONT_CSS = (BUILD / "inter.css").read_text()

BASE_CSS = f"""
{FONT_CSS}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
html, body {{ width: 100%; height: 100%; overflow: hidden; }}
body {{
  font-family: Inter, 'Liberation Sans', sans-serif;
  -webkit-font-smoothing: antialiased;
  color: {CHALK};
  background: {INK};
}}
.canvas {{
  position: relative; width: 100%; height: 100%; overflow: hidden;
  background:
    radial-gradient(115% 62% at 50% 4%, var(--glow) 0%, transparent 62%),
    linear-gradient(180deg, {INK_TOP} 0%, {INK} 52%, {INK_BOTTOM} 100%);
}}
/* A hairline of the state accent, and nothing else coloured on the canvas. */
.eyebrow {{
  display: inline-flex; align-items: center; gap: calc(14px * var(--s));
  font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase;
  color: var(--accent); font-size: calc(26px * var(--s));
}}
.eyebrow::before {{
  content: ''; width: calc(40px * var(--s)); height: calc(5px * var(--s));
  border-radius: 999px; background: var(--accent);
}}
h1 {{
  font-weight: 800; letter-spacing: -0.025em; line-height: 1.08;
  font-size: calc(70px * var(--s)); white-space: pre-line; text-wrap: balance;
}}
p.sub {{
  font-weight: 400; line-height: 1.42; color: {CHALK_DIM};
  font-size: calc(30px * var(--s)); text-wrap: balance;
}}
.frame {{
  border-radius: calc(46px * var(--s));
  box-shadow:
    0 calc(44px * var(--s)) calc(96px * var(--s)) calc(-24px * var(--s)) rgba(0,0,0,.80),
    0 0 0 1px rgba(230,237,243,.11);
  overflow: hidden; background: {INK};
}}
.frame img {{ display: block; width: 100%; height: auto; }}
"""

PORTRAIT_CSS = """
.canvas { display: flex; flex-direction: column; align-items: center; }
/* The copy block is a fixed height with its contents centred, so a caption
   that wraps to three lines does not shove the device down the canvas. Every
   screenshot in the set then shows the handset at exactly the same y, which is
   what makes them read as one strip in the Play carousel. */
.copy { height: 440px; padding: 0 84px; text-align: center; display: flex;
        flex-direction: column; align-items: center; justify-content: center;
        gap: 26px; }
h1 { max-width: 900px; }
p.sub { max-width: 820px; }
.frame { width: 624px; }
"""

# A 16:10 tablet capture on a 16:9 canvas cannot fill the height if the caption
# sits beside it, and the leftover bands read as a mistake. Stacking the caption
# above and sizing the device off the canvas *height* keeps the screenshot
# nearly full-bleed. Height rather than width, because the height is the scarce
# axis here - fix that and the width follows.
LANDSCAPE_CSS = """
.canvas { display: flex; flex-direction: column; align-items: center;
          justify-content: center; gap: calc(46px * var(--s));
          padding: calc(40px * var(--s)); }
.copy { display: flex; flex-direction: column; align-items: center;
        text-align: center; gap: calc(20px * var(--s)); }
h1 { font-size: calc(58px * var(--s)); }
p.sub { font-size: calc(28px * var(--s)); max-width: calc(1300px * var(--s)); }
.frame { height: 74%; width: auto; }
.frame img { height: 100%; width: auto; }
"""

PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><style>{css}</style></head>
<body><div class="canvas" style="--accent:{accent};--glow:{glow};--s:{scale}">
  <div class="copy">
    <span class="eyebrow">{eyebrow}</span>
    <h1>{headline}</h1>
    <p class="sub">{sub}</p>
  </div>
  <div class="frame"><img src="{img}"></div>
</div></body></html>
"""


def glow_of(accent: str) -> str:
    """The accent at ~13% alpha, as the only tint on the background."""
    return accent + "22"


def render(page_html: str, width: int, height: int, out: Path) -> None:
    if not FIREFOX:
        sys.exit("firefox not found - it is the renderer")
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "page.html"
        src.write_text(page_html)
        shot = Path(tmp) / "shot.png"
        subprocess.run(
            [
                FIREFOX, "--headless",
                "--profile", tmp,
                "--window-size", f"{width},{height}",
                "--screenshot", str(shot),
                src.as_uri(),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=180,
        )
        if not shot.exists():
            sys.exit(f"firefox produced nothing for {out.name}")
        flatten(shot, out)


def flatten(src: Path, out: Path, alpha: bool = False) -> None:
    """Play rejects screenshots with an alpha channel; the icon needs one."""
    if not FFMPEG:
        sys.exit("ffmpeg not found - needed to drop the alpha channel")
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [FFMPEG, "-y", "-loglevel", "error", "-i", str(src),
         "-pix_fmt", "rgba" if alpha else "rgb24", str(out)],
        check=True,
    )


def build_screenshots(kinds: list[str]) -> list[Path]:
    written = []
    for kind in kinds:
        canvas = CANVASES[kind]
        css = BASE_CSS + (PORTRAIT_CSS if canvas.layout == "portrait" else LANDSCAPE_CSS)
        for shot in canvas.shots:
            src = RAW / shot.source
            page = PAGE.format(
                css=css,
                accent=shot.accent,
                glow=glow_of(shot.accent),
                scale=canvas.scale,
                eyebrow=html.escape(shot.eyebrow),
                headline=html.escape(shot.headline),
                sub=html.escape(shot.sub),
                img=data_uri(src),
            )
            out = SHOTS / kind / shot.out
            render(page, canvas.width, canvas.height, out)
            written.append(out)
            print(f"  {out.relative_to(ROOT)}  {canvas.width}x{canvas.height}")
    return written


# --- icon and feature graphic ------------------------------------------------

# Redrawn from app/src/main/res/drawable/ic_launcher_{background,foreground}.xml.
# The viewBox is the adaptive icon's 72dp safe zone out of 108dp, so the Play
# icon frames the mark the way a launcher actually crops it.
ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"
     viewBox="18 18 72 72">
  <rect x="18" y="18" width="72" height="72" fill="{ink}"/>
  <circle cx="54" cy="54" r="34" fill="none" stroke="{green}" stroke-opacity="0.10" stroke-width="1.5"/>
  <circle cx="54" cy="54" r="46" fill="none" stroke="{green}" stroke-opacity="0.07" stroke-width="1.5"/>
  <circle cx="54" cy="54" r="22" fill="{green}"/>
  <g transform="translate(42 42.5)" fill="{ink}">
    <path d="M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"/>
  </g>
</svg>"""


def icon_markup() -> str:
    return ICON_SVG.format(ink=INK, green=GREEN)


def build_icon() -> Path:
    out = GRAPHICS / "icon-512.png"
    page = (
        "<!doctype html><html><head><meta charset='utf-8'><style>"
        "*{margin:0;padding:0}html,body{width:512px;height:512px;overflow:hidden;"
        "background:transparent}svg{display:block}</style></head><body>"
        + icon_markup()
        + "</body></html>"
    )
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "icon.html"
        src.write_text(page)
        shot = Path(tmp) / "icon.png"
        subprocess.run(
            [FIREFOX, "--headless", "--profile", tmp,
             "--window-size", "512,512", "--screenshot", str(shot), src.as_uri()],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180,
        )
        flatten(shot, out, alpha=True)
    print(f"  {out.relative_to(ROOT)}  512x512")
    return out


FEATURE_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><style>
{font}
*{{margin:0;padding:0;box-sizing:border-box}}
html,body{{width:1024px;height:500px;overflow:hidden}}
body{{
  font-family:Inter,'Liberation Sans',sans-serif;-webkit-font-smoothing:antialiased;
  color:{chalk};
  background:
    radial-gradient(70% 130% at 50% 50%, {green}1C 0%, transparent 62%),
    linear-gradient(135deg, {ink_top} 0%, {ink} 55%, {ink_bottom} 100%);
  display:flex;align-items:center;justify-content:center;gap:56px;padding:0 72px;
}}
/* Play crops this graphic on some surfaces, so nothing meaningful goes near
   the edges and the mark stays inside the middle band. */
.mark{{flex:0 0 176px;width:176px;height:176px;border-radius:40px;overflow:hidden;
  box-shadow:0 24px 60px -18px rgba(0,0,0,.8), 0 0 0 1px rgba(230,237,243,.12)}}
.mark svg{{width:100%;height:100%;display:block}}
.name{{font-weight:800;font-size:74px;letter-spacing:-0.03em;line-height:1}}
.tag{{font-weight:600;font-size:31px;letter-spacing:-0.01em;margin-top:18px;line-height:1.25}}
.meta{{margin-top:26px;display:flex;gap:14px;flex-wrap:wrap}}
.chip{{font-weight:600;font-size:20px;letter-spacing:.04em;color:{dim};
  border:1px solid rgba(230,237,243,.16);border-radius:999px;padding:9px 18px}}
</style></head>
<body>
  <div class="mark">{icon}</div>
  <div>
    <div class="name">PTTdroid</div>
    <div class="tag">Push-to-talk over a relay you run yourself</div>
    <div class="meta">
      <span class="chip">NO ACCOUNT</span>
      <span class="chip">NO ADS</span>
      <span class="chip">OPEN SOURCE</span>
    </div>
  </div>
</body></html>
"""


def build_feature() -> Path:
    out = GRAPHICS / "feature-graphic-1024x500.png"
    page = FEATURE_PAGE.format(
        font=FONT_CSS, chalk=CHALK, dim=CHALK_DIM, green=GREEN,
        ink=INK, ink_top=INK_TOP, ink_bottom=INK_BOTTOM, icon=icon_markup(),
    )
    render(page, 1024, 500, out)
    print(f"  {out.relative_to(ROOT)}  1024x500")
    return out


# --- verification ------------------------------------------------------------

MB = 1024 * 1024


def verify() -> int:
    """Re-check every produced file against Play's published constraints."""
    problems = []

    def check(path: Path, *, exact=None, side=None, ratios=None, max_bytes=None):
        w, h = png_size(path)
        rel = path.relative_to(ROOT)
        if exact and (w, h) != exact:
            problems.append(f"{rel}: {w}x{h}, expected {exact[0]}x{exact[1]}")
        if side and not (side[0] <= w <= side[1] and side[0] <= h <= side[1]):
            problems.append(f"{rel}: {w}x{h} outside {side[0]}-{side[1]} px per side")
        if ratios:
            r = w / h
            if not any(abs(r - t) < 0.005 for t in ratios):
                problems.append(f"{rel}: ratio {r:.4f} is neither 16:9 nor 9:16")
        size = path.stat().st_size
        if max_bytes and size > max_bytes:
            problems.append(f"{rel}: {size / MB:.1f} MB over the {max_bytes / MB:.0f} MB cap")

    r16_9, r9_16 = 16 / 9, 9 / 16

    phone = sorted((SHOTS / "phone").glob("*.png"))
    if not 2 <= len(phone) <= 8:
        problems.append(f"phone: {len(phone)} screenshots, Play takes 2-8")
    for p in phone:
        check(p, side=(320, 3840), ratios=(r16_9, r9_16), max_bytes=8 * MB)

    for kind in ("tablet-7", "tablet-10"):
        tabs = sorted((SHOTS / kind).glob("*.png"))
        if tabs and not 2 <= len(tabs) <= 8:
            problems.append(f"{kind}: {len(tabs)} screenshots, Play takes 2-8")
        for p in tabs:
            check(p, side=(1080, 7680), ratios=(r16_9, r9_16), max_bytes=8 * MB)

    feature = GRAPHICS / "feature-graphic-1024x500.png"
    if feature.exists():
        check(feature, exact=(1024, 500), max_bytes=15 * MB)

    icon = GRAPHICS / "icon-512.png"
    if icon.exists():
        check(icon, exact=(512, 512), max_bytes=1 * MB)

    # Play rejects screenshots and the feature graphic that carry alpha; the
    # icon is required to have it. ffmpeg's pix_fmt is what we set, so read the
    # PNG colour type back out of IHDR rather than trusting the call.
    for p in phone + sorted(SHOTS.glob("tablet-*/*.png")) + [feature]:
        if p.exists() and colour_type(p) not in (0, 2, 3):
            problems.append(f"{p.relative_to(ROOT)}: has an alpha channel, Play rejects it")
    if icon.exists() and colour_type(icon) not in (4, 6):
        problems.append(f"{icon.relative_to(ROOT)}: needs a 32-bit PNG with alpha")

    for line in problems:
        print(f"  FAIL {line}")
    if not problems:
        print("  all assets satisfy Play's size, ratio, format and count rules")
    return len(problems)


def colour_type(path: Path) -> int:
    return path.open("rb").read(26)[25]


def check_text() -> int:
    """Play's hard character limits on the listing text."""
    limits = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
    problems = 0
    for name, cap in limits.items():
        f = ROOT / "listing" / name
        if not f.exists():
            continue
        n = len(f.read_text().rstrip("\n"))
        flag = "FAIL" if n > cap else "ok  "
        if n > cap:
            problems += 1
        print(f"  {flag} listing/{name}: {n}/{cap} characters")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["phone", "tablet", "graphics", "verify"])
    args = ap.parse_args()

    if args.only != "verify":
        if args.only in (None, "phone"):
            print("phone screenshots")
            build_screenshots(["phone"])
        if args.only in (None, "tablet"):
            print("tablet screenshots")
            build_screenshots(["tablet-10", "tablet-7"])
        if args.only in (None, "graphics"):
            print("store graphics")
            build_icon()
            build_feature()

    print("verifying against Play's requirements")
    bad = verify() + check_text()
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
