#!/usr/bin/env python3
"""
hangul_to_taps.py — Convert a Hangul string into a Gboard Dubeolsik (두벌식)
on-screen key tap sequence, for testing CJK *composition* on a device/emulator.

WHY THIS EXISTS
---------------
`adb shell input text "삼성"` and the mobile MCP `input(text=...)` COMMIT text
directly — they never drive the IME composition region (setComposingText →
commitText). CJK input bugs (e.g. the InvestingBasicTextField composition-region
corruption fixed in PR #7857) only reproduce when the IME actually *composes*
syllables. To exercise that path you must tap the real on-screen jamo keys so
Gboard assembles Hangul. This script emits exactly that tap sequence.

PREREQUISITES
-------------
- Gboard with the Korean (한국어, 두벌식 / Dubeolsik) layout added AND currently
  active on screen (long-press space or the globe key to switch to it first).
- The text field is focused and the Korean keyboard is fully docked & visible.

COORDINATES
-----------
KEYS below are in DEVICE pixels for the standard Android emulator
`sdk_gphone64_x86_64` at 1080x2400 with Gboard's default-height Korean layout.
Every coordinate here was verified against live composition in a real QA run
(삼성전자 / 한국). The `adb` output (default) uses these device pixels directly and
is resolution-bound but MCP-scaling-independent.

The `mcp` output divides by SCALE (default 2.5) because the mobile MCP `input tap`
interprets coordinates in the *screen-capture* pixel space (captures come back
432x960 on this emulator = device / 2.5), NOT raw device pixels. If your captures
come back at a different width, pass --scale (e.g. 540-wide capture → --scale 2.0).

RE-CALIBRATION: if keys land wrong (different device, keyboard height, or one-handed
mode), screenshot the Korean keyboard, read the key centers, and update KEYS / ROW_Y.

USAGE
-----
  python3 hangul_to_taps.py 삼성전자                  # default: adb shell input tap lines
  python3 hangul_to_taps.py --format mcp 삼성전자      # mobile MCP flow-batch JSON
  python3 hangul_to_taps.py --serial emulator-5554 한국
  python3 hangul_to_taps.py --wait 120 --device "" 삼성전자
"""

import argparse
import json
import sys

# --- Dubeolsik key centers in DEVICE pixels (1080x2400, default keyboard height) ---
ROW1_Y, ROW2_Y, ROW3_Y = 1725, 1913, 2035
KEYS = {
    # Row 1 (top): consonants + 5 vowels
    "ㅂ": (55, ROW1_Y), "ㅈ": (163, ROW1_Y), "ㄷ": (270, ROW1_Y), "ㄱ": (378, ROW1_Y),
    "ㅅ": (485, ROW1_Y), "ㅛ": (593, ROW1_Y), "ㅕ": (703, ROW1_Y), "ㅑ": (810, ROW1_Y),
    "ㅐ": (918, ROW1_Y), "ㅔ": (1025, ROW1_Y),
    # Row 2 (indented one half-key): consonants + vowels
    "ㅁ": (108, ROW2_Y), "ㄴ": (215, ROW2_Y), "ㅇ": (323, ROW2_Y), "ㄹ": (430, ROW2_Y),
    "ㅎ": (538, ROW2_Y), "ㅗ": (645, ROW2_Y), "ㅓ": (753, ROW2_Y), "ㅏ": (860, ROW2_Y),
    "ㅣ": (968, ROW2_Y),
    # Row 3: shift, consonants, vowels, delete
    "ㅋ": (215, ROW3_Y), "ㅌ": (325, ROW3_Y), "ㅊ": (433, ROW3_Y), "ㅍ": (540, ROW3_Y),
    "ㅠ": (648, ROW3_Y), "ㅜ": (755, ROW3_Y), "ㅡ": (863, ROW3_Y),
}
SHIFT = (85, ROW3_Y)
DELETE = (970, ROW3_Y)
SPACE = (540, 2180)

# Shifted single-tap keys (tap SHIFT, then the base key)
SHIFTED = {
    "ㄲ": "ㄱ", "ㄸ": "ㄷ", "ㅃ": "ㅂ", "ㅆ": "ㅅ", "ㅉ": "ㅈ",
    "ㅒ": "ㅐ", "ㅖ": "ㅔ",
}
# Compound vowels typed as two base jamo on Dubeolsik
COMPOUND_V = {
    "ㅘ": ["ㅗ", "ㅏ"], "ㅙ": ["ㅗ", "ㅐ"], "ㅚ": ["ㅗ", "ㅣ"],
    "ㅝ": ["ㅜ", "ㅓ"], "ㅞ": ["ㅜ", "ㅔ"], "ㅟ": ["ㅜ", "ㅣ"], "ㅢ": ["ㅡ", "ㅣ"],
}
# Compound finals (받침) typed as two base jamo
COMPOUND_T = {
    "ㄳ": ["ㄱ", "ㅅ"], "ㄵ": ["ㄴ", "ㅈ"], "ㄶ": ["ㄴ", "ㅎ"],
    "ㄺ": ["ㄹ", "ㄱ"], "ㄻ": ["ㄹ", "ㅁ"], "ㄼ": ["ㄹ", "ㅂ"], "ㄽ": ["ㄹ", "ㅅ"],
    "ㄾ": ["ㄹ", "ㅌ"], "ㄿ": ["ㄹ", "ㅍ"], "ㅀ": ["ㄹ", "ㅎ"], "ㅄ": ["ㅂ", "ㅅ"],
}

# Compatibility-jamo tables (U+31xx) — match KEYS keys directly.
L_LIST = list("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")
V_LIST = list("ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ")
T_LIST = [""] + list("ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ")

SBASE, LBASE_COUNT, VCOUNT, TCOUNT = 0xAC00, 19, 21, 28
NCOUNT = VCOUNT * TCOUNT          # 588
SCOUNT = LBASE_COUNT * NCOUNT     # 11172


def syllable_to_jamo(ch):
    """Decompose a precomposed Hangul syllable into its base jamo list."""
    code = ord(ch)
    if not (SBASE <= code < SBASE + SCOUNT):
        return None  # not a precomposed syllable
    idx = code - SBASE
    l = idx // NCOUNT
    v = (idx % NCOUNT) // TCOUNT
    t = idx % TCOUNT
    out = [L_LIST[l], V_LIST[v]]
    if t:
        out.append(T_LIST[t])
    return out


def expand(jamo):
    """Expand compound vowels/finals into base-key jamo sequences."""
    if jamo in COMPOUND_V:
        return COMPOUND_V[jamo]
    if jamo in COMPOUND_T:
        return COMPOUND_T[jamo]
    return [jamo]


def text_to_keytaps(text):
    """Return an ordered list of ('key'|'shift'|'space', x, y) tuples to type `text`."""
    taps = []
    for ch in text:
        if ch == " ":
            taps.append(("space", *SPACE))
            continue
        jamo_seq = syllable_to_jamo(ch)
        if jamo_seq is None:
            # Maybe it's already a standalone compatibility jamo (e.g. "ㄱ")
            jamo_seq = [ch] if ch in KEYS or ch in SHIFTED else None
        if jamo_seq is None:
            sys.stderr.write(f"WARN: skipping non-Hangul char {ch!r}\n")
            continue
        for jamo in jamo_seq:
            for base in expand(jamo):
                if base in SHIFTED:
                    taps.append(("shift", *SHIFT))
                    base = SHIFTED[base]
                if base not in KEYS:
                    sys.stderr.write(f"WARN: no key mapping for jamo {base!r}\n")
                    continue
                taps.append(("key", *KEYS[base]))
    return taps


def emit_adb(taps, wait_ms, serial):
    pref = f"adb -s {serial} shell" if serial else "adb shell"
    lines = ["#!/usr/bin/env bash", "set -e"]
    sec = wait_ms / 1000.0
    for kind, x, y in taps:
        lines.append(f"{pref} input tap {x} {y}   # {kind}")
        lines.append(f"sleep {sec:g}")
    return "\n".join(lines)


def emit_mcp(taps, wait_ms, scale):
    cmds = []
    for _kind, x, y in taps:
        cmds.append({"name": "input_tap",
                     "arguments": {"x": round(x / scale), "y": round(y / scale)}})
        cmds.append({"name": "system_wait", "arguments": {"ms": wait_ms}})
    return json.dumps(cmds, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser(description="Hangul → Gboard Dubeolsik tap sequence")
    ap.add_argument("text", help="Hangul string to compose, e.g. 삼성전자")
    ap.add_argument("--format", choices=["adb", "mcp"], default="adb",
                    help="adb: `adb shell input tap` device-pixel lines (default). "
                         "mcp: mobile MCP flow-batch JSON in capture-pixel space.")
    ap.add_argument("--wait", type=int, default=130,
                    help="ms between taps so Gboard composes each jamo (default 130)")
    ap.add_argument("--serial", default="",
                    help="adb device serial for --format adb (default: first device)")
    ap.add_argument("--scale", type=float, default=2.5,
                    help="device-px / capture-px ratio for --format mcp (default 2.5 "
                         "for a 432-wide capture on a 1080-wide device)")
    args = ap.parse_args()

    taps = text_to_keytaps(args.text)
    if not taps:
        sys.stderr.write("No tappable characters produced.\n")
        sys.exit(1)
    if args.format == "adb":
        print(emit_adb(taps, args.wait, args.serial))
    else:
        print(emit_mcp(taps, args.wait, args.scale))


if __name__ == "__main__":
    main()
