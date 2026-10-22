# Phase 7.5 FIX-1 — Global tap highlight (press flood) fix

## Problem (user, on device, Android 8.1 / API 27)

Touching or holding ANY row or button (settings/preference rows, text rows,
buttons) painted a big light white/grey RECTANGULAR highlight slab over the
whole item. Screenshots showed preference rows flooded solid light grey on
press. User called it the most annoying issue in the Phase 7 build.

## Root cause

One central binding in `res/values/styles.xml`:

- `AppTheme` set `android:colorControlHighlight` to `?attr/textColor`.
- `@color/textColor` is **#FFFFFF — fully opaque** (colors.xml line 15).

`colorControlHighlight` is the single color behind:
- the framework ripple / pressed flood used by `?attr/selectableItemBackground`
  (98 layout usages) — this is what preference rows and most rows/buttons use;
- every project `RippleDrawable` (all three already reference
  `?attr/colorControlHighlight` — see below);
- Material button ripples that don't override `rippleColor`.

On API 27 the pressed state renders this color at full strength across the
view's bounds, so an opaque-white highlight = a solid light-grey slab over the
whole row. No per-screen fix was needed — the lever is the theme attr itself.

## Old values per theme (before this fix)

No variant overlay overrode `android:colorControlHighlight`; all of them
inherited AppTheme's `?attr/textColor`, resolved through each mode's own
`textColor` rebind:

| Theme | Resolved highlight | Effect |
|---|---|---|
| AppTheme (default dark) | #FFFFFF (opaque) | solid white/grey slab on dark |
| AmoledMode | #FFFFFF (opaque) | same |
| DraculaMode | #F8F8F2 (opaque) | same |
| SilentBlueMode | #FFFFFF (opaque) | same |
| MonetMode | material_dynamic_neutral90 (opaque light) | same |
| LightMode | #111111 (opaque) | solid near-black slab on light bg (same bug, inverted) |
| LavenderMode | #2d1b47 (opaque) | solid dark slab on light bg |

## Fix (all in `res/values/styles.xml`)

1. **AppTheme** — `android:colorControlHighlight`:
   `?attr/textColor` → **`#1FFFFFFF`** (12% white). Dark overlays (Amoled,
   Dracula, SilentBlue, Monet) inherit this; they never overrode the attr.
2. **LightMode** — added `android:colorControlHighlight` = **`#14000000`**
   (8% black), so light surfaces get a faint dark wash instead of the
   near-invisible/wrong white.
3. **LavenderMode** — added the same **`#14000000`** (it is a light theme:
   `isLightTheme=true`).

Feedback is NOT removed (a11y) — it is now a faint wash instead of a flood.

## Ripple drawables — no color edits needed

All three project ripples already draw with `?attr/colorControlHighlight`, so
the central fix aligns them automatically:

- `res/drawable/bg_ripple_rounded.xml` (settings rows, episode rows) — only its
  header comment was refreshed (it still claimed AppTheme binds the attr to
  `?attr/textColor`).
- `res/drawable/rounded_select_ripple.xml` — untouched.
- `res/drawable/bg_circle_overlay.xml` (details floating circles) — untouched.

## Per-style `rippleColor` hardcodes aligned (styles.xml)

These MaterialButton styles bypassed the theme attr with opaque colors; all
now use `?attr/colorControlHighlight`:

| Style | Was | 
|---|---|
| RoundedSelectableButton | `@color/textColor` (opaque #FFFFFF) |
| MultiSelectButton | `?attr/textColor` (opaque in both themes) |
| SyncButton | `?attr/white` (opaque #FFF on dark themes) |
| 3 player/TV button styles | `@color/white` (opaque) |

## Deliberately left untouched (listed, not fixed — outside this fix's scope)

- `rippleColor = @color/video_button_ripple` (#32FFFFFF, ~20% white) — a
  dedicated, already-translucent player token.
- `rippleColor = @color/black_button_ripple` and `@color/selectable_black`
  (SelectableButton) — dedicated button tokens; `selectable_black` is a dark
  ripple on a white button by design. Neither token is defined under this
  fork's `res/values/` (not grep-found) and neither is the flood source.
- `rippleColor = ?attr/primaryBlackBackground` — dark ripple by design.
- `android:colorEdgeEffect = ?attr/textColor` (AppTheme) — overscroll edge
  glow, a different feedback channel, not part of the complaint.
- `rating_fill.xml` / `rating_empty.xml` `state_pressed` items — star-drawable
  selectors for the rating bar; they swap icons, they paint no color flood.
- `outline_drawable_forced` (ListView selector) — focus outline only.
- Preference "activated" state: SwitchPreferenceCompat rows get their press
  feedback from `selectableItemBackground` → the fixed attr; the switch thumb
  itself uses `colorControlActivated` and was not part of the complaint.

## Validation

`values/styles.xml`, `values/colors.xml` (unchanged), `values-v31/styles.xml`
(unchanged — dialog themes only, no highlight items), and the three ripple
drawables all parse clean with `xml.dom.minidom`. Note: during editing, one
intermediate save dropped a `</item>` in LightMode; minidom validation caught
it (mismatched tag, line 195) and it was repaired before finishing — the final
files validate. CI is still the first compiler.
