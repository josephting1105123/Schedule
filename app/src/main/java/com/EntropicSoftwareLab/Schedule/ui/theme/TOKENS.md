# Design Tokens — Source of Truth (Schedule)

Light-only, blue accent on a light ink base — "glass" design system, palette taken from the user's Jarvis app (2026-11-15),
built on the 60-30-10 rule: neutral background/card ≈60%, ink text/structure ≈30%, one blue
accent ≈10%. The app had a dark theme; it was removed (the user's call) rather than kept unused, so
there is exactly one palette now. This file is the **single human-readable source of truth**.

**Provenance:** ported from Clipboard Share (`JosephClipboardClient/app/src/main/java/.../ui/theme/`),
which remains the origin of these values. Colour, spacing, shape, elevation, glass and motion tokens are
identical — diff against that project whenever a value changes on either side.

Surfaces in this app that must stay in lockstep:

1. **Compose** — `ui/theme/Color.kt`, `AppColors.kt`, `Dimens.kt`, `Elevation.kt`, `Motion.kt`, `Glass.kt`
2. **Glance widget** — `widget/WidgetTokens.kt`. Glance runs outside the Compose composition, so it cannot
   read `LocalAppColors`. The file now holds only what the Month widget still needs: its fallback
   event fill (`LightGreen`, with the deprecated alias `DarkGreen`) and its two on-fill inks
   (`OnLightFill`, `OnDarkFill`), plus `MonthWidgetColors`, a separate, deliberately fixed dark-plum
   design. The two Today widgets (Agenda, Day at a glance) don't use this file: they take their
   colours from `GlanceTheme` (wallpaper colours on Android 12 and later).

There is deliberately **no XML theme mirror** — this app is 100% Compose. `res/values/themes.xml` carries
only the startup window background, which tracks `background` below via `@color/window_background`.

## Deviations from Clipboard Share

- **Typography is single-voice serif.** Clipboard Share documents a two-voice system (IBM Plex Sans for
  content, Geist for controls); this app uses `FontFamily.Serif` on every M3 slot and bundles no font
  files. The size/weight scale is unchanged (Headline 24 / Title 18 / Body 15 / Caption 12 / Label 14),
  as is the 15sp body floor and the tabular-figures rule for live numbers.
- **New semantic use of existing colours** for schedule occupancy — see *Occupancy* below. No new hex
  values are introduced.

## Occupancy (Schedule-specific semantics)

| Meaning | Token | Rationale |
|---|---|---|
| Free slot | `accentMuted` fill, `greenText` label | Reads as calm/available without shouting |
| Conflict / overlap | `warning` | Informs, never blocks — conflicts are advisory |
| Per-calendar identity | source calendar's `DISPLAY_COLOR`, as a 3dp leading bar only | Keeps Google's saturated calendar colours from flooding the quiet surface |
| Per-event identity (Today hour grid) | a distinct `EventColorPalette` hue per event (custom colours kept), filling its share of the hour cell | The grid's whole job is telling a day's events apart at a glance — the thin-bar rule is deliberately relaxed here, and only here |

**`EventColorPalette`** (`ui/theme/EventColorPalette.kt`): twelve colours, not the app's accent's
family — `brand` (`Blue`, `Vermillion`, `Teal`, `Plum`, `Ochre`, `Slate`) leads the picker and backs
the automatic per-day assignment, followed by `Sky`, `Rose`, `Maroon`, `Green`, `Olive`, `Violet`.
Hues are drawn from two colour-blind-aware categorical sources — Okabe & Ito's "Color Universal
Design" set and IBM Carbon's data-visualisation palette — rather than tuned by eye, and deliberately
include only one green (`#15803D`, a close neighbour of `success` `#0A7F5C`), never a green ramp: the app's one green
is reserved for that semantic meaning, and no other event colour competes with the blue accent for
attention either.

The app is light-only, so an event fill only ever sits on one card, `#FFFFFF`, and every colour clears
WCAG AA (4.5:1) against it — measured worst case 4.70:1 (`Rose`). `EventColorPaletteTest` measures
this directly rather than assuming a luminance band clears it. The add-event colour picker writes the
chosen value to `EVENT_COLOR`, so a stored colour round-trips identically. The Today grid assigns
these per day via `BrandColorAssignment`; the agenda rows below reuse the same assignment so list and
grid correlate.

**Hour-grid geometry.** Two rules keep the grid unambiguous, and they are why `HourGrid.lanes`
exists:

- *Horizontal extent always means time.* A cell is filled in proportion to the part of the hour its
  event covers — an event ending at half past fills half the cell, one running 19:15–19:45 sits as a
  narrow bar offset into it.
- *A stacked row always means a clash.* Overlapping events go into separate lanes, each lane a
  further row of the same twelve hours, rather than splitting one cell between them. Lane count is
  the day's peak concurrency. Two slices share a cell only when one event ends and the next begins
  inside that same hour — they abut, they don't overlap.

## Colors

The last column names the equivalent attribute in Clipboard Share's XML theme — for cross-referencing
that project only; this app has no XML theme. There is one column of values now, not two: the app has
no dark theme.

| Token | Value | Compose val | Clipboard Share XML attr |
|---|---|---|---|
| background | `#F7F8FC` | `Light*` | `appBackgroundColor` |
| card | `#FFFFFF` | `Light*` | `appCardColor` |
| surfaceElevated | `#F7F8FC` | `Light*` | `appSurfaceElevated` |
| textPrimary | `#18202B` | `Light*` | `appTextPrimaryColor` |
| textSecondary | `#4F5A6B` | `Light*` | `appTextSecondaryColor` |
| divider | `#ECEDEE` | `Light*` | `appDividerColor` |
| hairline | `#18202B @8%` (`0x1418202B`) | `Light*` | `appHairline` |
| scrim | `#000000 @40%` (`0x66000000`) | `Light*` | `appScrim` |
| navBar | `#FFFFFF` | `Light*` | `appNavBarColor` |
| navIcon | `#4F5A6B` | `Light*` | `appNavIconColor` |
| bottomSheet | `#FFFFFF` | `Light*` | `appBottomSheetColor` |
| gradientTop | `#FFFFFF` | `Light*` | — (Compose-only) |
| gradientBottom | `#F1F3F9` | `Light*` | — (Compose-only) |
| green (fill) — **blue accent, name is historical** | `#2F6BFF` | `LightGreen` | `appGreen` |
| greenText (WCAG) — **blue, name is historical** | `#1F5AE6` | `LightGreenText` | `appGreenText` |
| accentMuted — **blue @12% on white, name is historical** | `#E6EDFF` | `LightAccentMuted` | — (Compose-only) |
| success | `#0A7F5C` | `LightSuccess` | `appSuccess` |
| info | `#1F5AE6` | `LightInfo` | `appInfo` |
| warning | `#A8650A` | `LightWarning` | `appWarning` |
| error / destructive | `#D93636` | `LightError` | `appError` |

**The accent is blue, not green.** `green`/`greenText`/`accentMuted` keep their names — renaming
them would touch every screen that reads `AppThemeTokens.colors.green` — but the colour underneath is
blue (`#2F6BFF` fill, `#1F5AE6` text, ~5.7:1 on white). Green now names exactly one thing in this app,
`success` (`#0A7F5C`), and nothing else. **Accent discipline (unchanged in shape):** `green` = fill
(white text ON it); `greenText` = text/link/small-icon form (≥4.5:1, here ~5.7:1).

## Dimens

Spacing (4/8 rhythm): `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32 · huge 48` (dp).
Radii: `xs 6 · sm 8 · md 12 · lg 16 · xl 20 · pill 999` (dp). Glass: `hairlineWidth 1 · blurRadius 24` (dp).
Controls: `touchTarget 48 · buttonHeight 56 · iconSm 18 · iconMd 24 · iconLg 32 · headerIcon 28` (dp).

## Elevation (`Elevation.kt`)

`level0 0 · level1 1 (card) · level2 3 (bento/raised) · level3 8 (popover) · modal 24` (dp).
Rendered as tint + hairline + soft shadow, **not** heavy M2 drop shadows.

## Motion (`Motion.kt`)

Signature easing `emphasized = cubic-bezier(0.16, 1, 0.3, 1)`; `standard = FastOutSlowIn`.
Durations: `durFast 120` (exit/collapse) · `durMed 220` (enter/expand/crossfade) · `durSlow 320` (hero), ms.
`pressScale 0.97` · `staggerStepMs 40` (cap ~8 items).
**Reduced motion:** `Motion.reduceMotion` is computed in `AppTheme` from `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
Use `motion.dur(x)` → returns 0 when reduced; skip stagger/press-scale/shimmer. Desktop has no OS flag →
drive the same flag from a Settings toggle (Phase 2).

## Glass recipe (`Glass.kt`)

`Surface(color = glass.surface)` → overlay `Modifier.background(glass.verticalTint())` →
`Modifier.border(dimens.hairlineWidth, glass.hairline, RoundedCornerShape(dimens.radiusLg))`.
Optional background-layer `Modifier.blur(glass.blurRadius)` gated by `glass.enableBlur` (Android 12+/`SDK_INT>=31`);
the flat tint + hairline is the load-bearing fallback.

## Typography (`Type.kt`) — single voice: serif

Every M3 slot draws from `FontFamily.Serif`, headings and controls alike. No font files are bundled;
Serif resolves to the platform serif (Noto Serif on Android), which brings Latin/Cyrillic/CJK coverage
without shipping a megabyte of TTF.

- `AppTypography`: headlineSmall 24 Medium · titleMedium 18 Medium · bodyLarge 15 Normal ·
  labelLarge 14 Medium · bodySmall 12 Normal. Body floor is 15sp; nothing drops below 12sp.
- `TabularFigures = TextStyle(fontFamily = Serif, fontFeatureSettings = "tnum")` — merge onto a base
  style for pure-number fields (event times, durations) so ticking values don't reflow; for mixed
  strings use `String.withTabularNumerals()` (`MixedTypeText.kt`).
