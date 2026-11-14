# A+ Connect: Design System

**Status:** authoritative for both clients. If code disagrees with this file, the code is wrong.
**Scope:** desktop (Electron, vanilla HTML/CSS/JS) and Android (Kotlin, Compose + Material 3 1.3.1).
**Owner decisions recorded here (step 1 review, 14 Nov 2026):**
- **Pages:** Connect, Files, Clipboard, Notifications, Settings on both clients. Mirror lives inside Connect on both.
- **Colour:** one blue accent; green is used only for success and connected states.
- **Android glass:** uses Haze `1.1.1` for backdrop blur.
- **Tray fix:** "Open File Drop" gets one additive preload listener (see §13).

---

## 1. Identity

### 1.1 One product, two expressions
A+ Connect is one product. Both clients share a single set of **primitive tokens** and the same **semantic names**. They differ only in how those tokens are expressed:

| | Desktop | Android |
|---|---|---|
| Input | Keyboard + pointer first | Touch first |
| Density | Compact (row 40–48 px, control 32–36 px) | Comfortable (row 56–72 dp, control 40–48 dp, target ≥ 48 dp) |
| Navigation | Glass sidebar + Ctrl/Cmd+K command palette | Glass bottom navigation bar, 4 destinations |
| Corners | One step tighter | One step rounder |
| Material | Glass chrome over an ambient canvas | Same, rendered with Haze |

**Name everywhere:** "A+ Connect". The Android app label changes from "A+ Connect Companion" to "A+ Connect" (`strings.xml`). Desktop copy may still say "the A+ Connect app on your phone" where that's clearer. No "ColorOS", "OPPO" or "Panta" in UI chrome. The phone's ROM version may still appear as data, labelled "System version".

### 1.2 Accent: blue
- **One hue:** blue. Light accent `#2457E0` (white text 5.97:1). Dark accent `#7AA2FF` (dark text `#0B1018` at 7.66:1).
- **Green is a status, never an action.** Connected, synced, sent and granted use green. Buttons, selection, links and focus use blue.
- **Why:**
  1. "Connected" is this product's most important status. If the accent were green, every button would read as a status.
  2. The old green `#00C37B` can't carry white text (2.31:1).
  3. The desktop is already blue, so this means less churn.
  4. A cool accent keeps glass clean, with no green cast through the blur.

### 1.3 Material character: "liquid glass, quietly"
Inspired by ColorOS 17's translucent layers and physical motion, held to a pro-tool standard:
- **Glass is for chrome. Content is solid.** Sidebar, sticky page headers, bottom bar, top app bar, floating selection bar, dialogs, command palette, menus and toasts are glass. Lists, cards, forms and the file browser are solid, for legibility and frame rate.
- Glass is a tinted, blurred, slightly saturated layer. It has a 1 px inner highlight on top and a hairline outer edge. There are no rainbow refractions, no animated sheen and no glass-on-glass stacking beyond two layers.
- An **ambient canvas** sits behind everything: the background plus two very soft accent glows (≤ 18% light, ≤ 14% dark). This gives the glass something to refract. It never moves while you work.

---

## 2. Token architecture

Tokens come in three layers. Components may only reference **semantic** or **component** tokens, never primitives or raw hex.

```text
primitive  (raw scales: --p-blue-600, --p-neutral-900, --p-radius-16, --p-dur-220)
   └─> semantic  (meaning: --surface, --text-2, --accent, --success, --focus-ring)
          └─> component  (one job: --btn-primary-bg, --row-hover-bg, --input-border)
```

- **Desktop:** CSS custom properties in `:root`, with a dark override under `:root[data-theme="dark"]`. The **existing names are kept** (`--bg --surface --surface-2 --text --text-2 --text-3 --border --border-strong --brand --brand-600 --brand-050 --brand-100 --success --danger --warn --r-* --s-* --shadow-* --font --dur`). New names extend them; there is no parallel system.
- **Android:** `ui/theme/Tokens.kt` holds the primitives as `Color`/`Dp` constants. `AplusTheme` builds a full M3 `ColorScheme` plus an `AplusColors` extension (successor to `ThemeColors`), exposed through `LocalAplusColors`. There is no more 6-colour parameter drilling.

### 2.1 Primitives: colour

| Token | Hex | | Token | Hex |
|---|---|---|---|---|
| neutral-0 | `#FFFFFF` | | blue-050 | `#EAF0FF` |
| neutral-025 | `#F7F9FC` | | blue-100 | `#D9E4FF` |
| neutral-050 | `#F1F4F9` | | blue-300 | `#9DBBFF` |
| neutral-100 | `#E6EBF2` | | blue-400 | `#7AA2FF` |
| neutral-200 | `#D3DAE5` | | blue-600 | `#2457E0` |
| neutral-400 | `#7D8AA0` | | blue-700 | `#1D47BF` |
| neutral-500 | `#5E6B80` | | blue-900 | `#17264A` |
| neutral-600 | `#4A5568` | | green-050 | `#E6F6EE` |
| neutral-700 | `#66738A` | | green-700 | `#0B7A47` |
| neutral-750 | `#8D99AB` | | green-300 | `#43D696` |
| neutral-800 | `#B4BECD` | | green-950 | `#0F2A20` |
| neutral-850 | `#273143` | | amber-050 | `#FFF3DF` |
| neutral-875 | `#1A2332` | | amber-700 | `#8F5300` |
| neutral-900 | `#131A26` | | amber-300 | `#FFB74D` |
| neutral-950 | `#0B1018` | | amber-950 | `#2E2210` |
| ink | `#0F1623` | | red-050 | `#FDECEC` |
| snow | `#EEF2F8` | | red-700 | `#C8282A` |
| | | | red-300 | `#FF8079` |
| | | | red-950 | `#331718` |

The neutrals are cool slate, matched to the blue so glass doesn't look muddy.

### 2.2 Semantic: colour (light / dark)

| Semantic (desktop var) | Android (`AplusColors` / M3 role) | Light | Dark | Use |
|---|---|---|---|---|
| `--bg` | `canvas` / `background` | neutral-050 | neutral-950 | App canvas under the ambient glow |
| `--surface` | `surface` / `surface` | neutral-0 | neutral-900 | Solid content: cards, lists |
| `--surface-2` | `surfaceSunken` / `surfaceContainerLow` | neutral-025 | neutral-875 | Wells, row hover, segmented track |
| `--surface-elevated` *(new)* | `surfaceElevated` / `surfaceContainerHigh` | neutral-0 | neutral-875 | Solid fallback for glass |
| `--glass` *(new)* | `glassChrome` | white @ 72% | neutral-900 @ 64% | Static chrome (nothing scrolls under it) |
| `--glass-strong` *(new)* | `glassOverlay` | white @ 86% | neutral-900 @ 86% | Chrome that content scrolls under, and overlays |
| `--glass-edge` *(new)* | `glassEdge` | white @ 60% (inner top) | white @ 8% | 1 px highlight |
| `--text` | `textPrimary` / `onSurface` | ink | snow | Titles, body |
| `--text-2` | `textSecondary` / `onSurfaceVariant` | neutral-600 | neutral-800 | Supporting text, labels |
| `--text-3` (**alias `--text-muted`**) | `textMuted` | neutral-500 | neutral-750 | Meta, timestamps, placeholders |
| `--border` | `divider` / `outlineVariant` | neutral-200 | neutral-850 | Decorative hairlines only |
| `--border-strong` | `outline` / `outline` | neutral-400 | neutral-700 | Inputs, toggles, anything that must be seen (≥ 3:1) |
| `--brand` (**alias `--accent`**) | `accent` / `primary` | blue-600 | blue-400 | Primary buttons, selection, links, focus |
| `--on-accent` *(new)* | `onPrimary` | neutral-0 | neutral-950 | Text/icons on accent |
| `--brand-600` (**alias `--accent-strong`**) | — / (hover) | blue-700 | blue-300 | Hover/pressed accent; accent text on tint |
| `--brand-050` (**alias `--accent-subtle`**) | `accentSubtle` / `primaryContainer` | blue-050 | blue-900 | Selected row/nav, tonal button |
| `--brand-100` | `accentSubtleStrong` | blue-100 | `#1E3363` | Tonal button hover |
| `--success` / `--success-050` | `success` / `successSubtle` | green-700 / green-050 | green-300 / green-950 | Connected, synced, sent, granted |
| `--warn` / `--warn-050` *(new)* | `warning` / `warningSubtle` | amber-700 / amber-050 | amber-300 / amber-950 | Connecting, needs attention |
| `--danger` / `--danger-050` | `danger` / `error` / `errorContainer` | red-700 / red-050 | red-300 / red-950 | Failed, destructive |
| `--focus-ring` *(new)* | `focusRing` | blue-600 | blue-400 | 2 px ring, 2 px offset |
| `--scrim` *(new)* | `scrim` | ink @ 28% | black @ 52% | Behind dialogs |

**Measured contrast** (WCAG 2.x, computed from the hex values above):

| Pair | Light | Dark | Required |
|---|---|---|---|
| text / surface | 18.11 | 15.53 | 4.5 |
| text-2 / surface | 7.53 | 9.30 | 4.5 |
| text-3 / surface | 5.40 | 6.05 | 4.5 |
| text-3 / bg | 4.90 | 6.61 | 4.5 |
| on-accent / accent | 5.97 | 7.66 | 4.5 |
| accent (link/icon) / surface | 5.97 | 7.01 | 4.5 |
| accent-strong / accent-subtle | 6.80 | 7.78 | 4.5 |
| border-strong / surface (inputs) | 3.49 | 3.64 | 3.0 |
| success / success-subtle | 4.83 | 8.23 | 4.5 |
| warn / warn-subtle | 5.62 | 8.97 | 4.5 |
| danger / danger-subtle | 4.85 | 6.75 | 4.5 |
| focus ring / bg | 5.42 | 7.66 | 3.0 |
| text-3 on static glass (over canvas + glow) | 4.91 | 5.79 | 4.5 |
| text-2 on strong glass, worst case behind | 5.59 | 6.28 | 4.5 |
| text-3 on strong glass, worst case behind | **4.01** | **4.08** | 4.5, so the rule below applies |

**Glass legibility rule:** on `--glass-strong` (anything content scrolls beneath), the faintest allowed text is `--text-2`. `--text-3` is allowed on `--glass` only, because nothing scrolls under it.

### 2.3 Component tokens (examples; full list in `style.css` / `Tokens.kt`)

```text
--btn-primary-bg: var(--accent)            --btn-primary-fg: var(--on-accent)
--btn-primary-bg-hover: var(--accent-strong)
--btn-tonal-bg: var(--accent-subtle)       --btn-tonal-fg: var(--accent-strong)
--btn-ghost-bg-hover: var(--surface-2)     --btn-danger-fg: var(--danger)
--input-bg: var(--surface)                 --input-border: var(--border-strong)
--input-border-focus: var(--accent)
--row-hover-bg: var(--surface-2)           --row-selected-bg: var(--accent-subtle)
--nav-item-active-bg: var(--accent-subtle) --nav-item-active-fg: var(--accent-strong)
--pill-success-bg: var(--success-050)      --pill-success-fg: var(--success)
--toast-bg: var(--glass-strong)            --palette-bg: var(--glass-strong)
--dropzone-border: var(--border-strong)    --dropzone-active-bg: var(--accent-subtle)
--progress-track: var(--border)            --progress-fill: var(--accent)
```

---

## 3. Typography

**Font strategy**
- **Desktop:** the system stack, unchanged: `system-ui, -apple-system, "Segoe UI Variable Text", "Segoe UI", Roboto, "Noto Sans", sans-serif`. On KDE that resolves to Noto Sans. Nothing is downloaded, so it works offline.
- **Android:** `FontFamily.Default`, which is the system font (OPPO Sans on ColorOS, Roboto elsewhere), so the app feels native on each phone.
- **Mono, desktop:** `ui-monospace, "JetBrains Mono", "Noto Sans Mono", "DejaVu Sans Mono", monospace`.
- **Mono, Android:** `FontFamily.Monospace`.

**Tabular / mono treatment.** IP addresses, ports, pairing codes, file sizes, percentages, durations and paths always use one of these:
- `--font-mono` for codes and paths: pairing code, IP:port, breadcrumb, fingerprints.
- `font-variant-numeric: tabular-nums` (Android: `fontFeatureSettings = "tnum"`) for changing numbers in proportional text: progress %, battery, sizes, counts. This keeps them from jittering.
- The pairing code is shown in groups of three (`482 913`) at **mono 24/32**, letter-spacing 0.08em.

| Role | Desktop px (size/line, weight) | Android sp | M3 role | Used for |
|---|---|---|---|---|
| display | 26/32, 650 | 28/36, 600 | `headlineMedium` | Empty-state title, Connect hero device name |
| title-lg | 20/28, 650 | 22/28, 600 | `titleLarge` | Page title |
| title | 16/24, 600 | 17/24, 600 | `titleMedium` | Card/section title, dialog title |
| body | 14/20, 400 | 15/22, 400 | `bodyLarge` | Default text |
| body-sm | 13/18, 400 | 14/20, 400 | `bodyMedium` | Row subtitles, descriptions |
| label | 13/16, 550 | 14/20, 500 | `labelLarge` | Buttons, tabs, nav |
| caption | 12/16, 500 | 12/16, 500 | `labelMedium` | Meta, timestamps, section headers |
| mono | 12.5/18, 450 | 13/18, 400 | custom | Paths, IPs, codes |
| mono-lg | 24/32, 500 | 24/32, 500 | custom | Pairing code |

**Rules**
- **Sentence case everywhere**, section headers included ("Permissions", not "PERMISSIONS & SERVICES"). Avoid all-caps; where unavoidable, use at most 2 words with +0.04em tracking.
- Weights are limited to 400, 500/550, 600/650.
- Line length is at most 72ch in descriptions.

---

## 4. Spacing: 4 px rhythm

| Token | Value | Tier | When to use |
|---|---|---|---|
| `--s-1` | 4 | hairline | Icon ↔ text inside a pill, stacked meta lines |
| `--s-2` | 8 | tight | Inside controls, between related buttons |
| `--s-3` | 12 | snug | Row inner gap, card inner gap (desktop) |
| `--s-4` | 16 | base | Card padding (desktop), list row padding-x, Android inner gap |
| `--s-5` | 20 | comfy | Android card padding, Android screen margin |
| `--s-6` | 24 | section | Between groups on a page, dialog padding |
| `--s-8` | 32 | page | Desktop page padding, before a new page section |
| `--s-10` | 40 | hero | Empty-state padding |

Android uses the same values in dp. Screen margin: 20 dp desktop-class, 16 dp under 360 dp wide. Between cards in a list of cards: 12 desktop, 12 Android. Between sections: 24 / 28.

---

## 5. Radius, borders, dividers, elevation

### 5.1 Radius (same primitives, one step apart)

| Semantic | Desktop | Android |
|---|---|---|
| `--r-xs` (checkbox, tag) | 6 | 8 |
| `--r-sm` (button, input, row, segmented) | 8 → **10** | 12 |
| `--r-md` (tile, inner well, toast) | 12 | 16 |
| `--r-lg` (card, list, browser) | 16 | 20 |
| `--r-xl` (dialog, palette, sheet) | 22 | 28 |
| `--r-pill` | 999 | 50% |

A nested radius is the outer radius minus the padding, so it stays concentric. A 16 px card with 8 px padding holds a 8 px well.

### 5.2 Borders and dividers
- **Decorative hairline:** 1 px `--border`, between rows and around solid cards.
- **Meaningful boundary:** 1 px `--border-strong`, for inputs, switches off-track, the dropzone (1.5 px dashed) and segmented outlines. Measured ≥ 3:1.
- **Dividers:** list rows use a full-bleed hairline in dense lists, and an inset one (from the text start) in settings-style lists.
- **Selection** is never shown by a border-width change (that shifts layout). Use background plus an inset 2 px box-shadow or an indicator bar.

### 5.3 Elevation and glass

| Level | Name | Desktop recipe | Android recipe |
|---|---|---|---|
| 0 | Canvas | `--bg` + 2 radial glows | `background` + 2 radial `Brush` glows |
| 1 | Content | `--surface`, 1 px `--border`, `--shadow-sm` | `surface`, 1 dp `outlineVariant`, tonal 0 |
| 2 | Chrome (glass) | `--glass` or `--glass-strong`, `backdrop-filter: blur(24px) saturate(160%)`, inner top 1 px `--glass-edge`, outer 1 px `--border` | Haze `hazeChild(style = AplusGlass.chrome)` blur 24 dp, tint as tokens; API < 31 uses the fallback tint |
| 3 | Overlay (glass) | `--glass-strong`, blur 32px saturate 180%, `--shadow-lg`, over `--scrim` | Dialogs/sheets: Haze overlay style + scrim |

Shadows (light / dark):
- `--shadow-sm`: `0 1px 2px rgb(15 22 35 / .06)` / `0 1px 2px rgb(0 0 0 / .4)`
- `--shadow-md`: `0 8px 24px rgb(15 22 35 / .08)` / `0 8px 24px rgb(0 0 0 / .5)`
- `--shadow-lg`: `0 24px 64px rgb(15 22 35 / .16)` / `0 24px 64px rgb(0 0 0 / .6)`

**Reduce transparency** (Settings toggle on both platforms; the desktop also honours `prefers-reduced-transparency` where Chromium supports it): every glass token becomes its solid `--surface-elevated` equivalent and blur is off. Desktop stores this in `localStorage` (`aplus-transparency`), with no IPC. Android stores it in `CompanionSettings`, which is a local preference.

**Performance guardrails:**
- No more than 3 blurred layers on screen at once.
- Never blur a scrolling list itself.
- Desktop glass layers get `will-change` only while animating.

---

## 6. Motion

### 6.1 Tokens

| Token | Value | Use |
|---|---|---|
| `--dur-press` | 90 ms | Press-down feedback |
| `--dur-fast` | 140 ms | Hover, toggle, colour change, exit of small things |
| `--dur-base` | 220 ms | Enter of small surfaces (toast, menu, row) |
| `--dur-slow` | 320 ms | Dialog / palette / page enter |
| Exit | 0.7 × enter (100 / 160 / 220) | Exits are always faster than entries |
| `--ease-standard` | `cubic-bezier(.2, 0, 0, 1)` | Most transitions |
| `--ease-enter` | `cubic-bezier(.05, .7, .1, 1)` | Things arriving (emphasized decelerate) |
| `--ease-exit` | `cubic-bezier(.3, 0, .8, .15)` | Things leaving (accelerate) |
| `--ease-spring` | CSS `linear()` curve sampled from a spring with damping 0.82, stiffness 380 | Press release, selection indicator, panel open |

`--dur` (the existing 180 ms token) is kept as an alias of `--dur-fast` for old rules until step 8 removes it.

**Android equivalents** (`AplusMotion`):
- `spring(dampingRatio = 0.82f, stiffness = 380f)` for the default spring.
- `spring(0.68f, 500f)` for press release (a slight ColorOS overshoot).
- `spring(0.9f, 220f)` for page/sheet movement.
- `tween` durations and easing as above (`CubicBezierEasing`).

### 6.2 Rules
1. **Press feedback ≤ 150 ms:** scale to 0.97 (buttons, tiles) or 0.985 (cards, rows) using `transform` / `graphicsLayer`. Layout bounds are never animated, and hit areas never move.
2. **Release uses the spring.** That release is the ColorOS-like "bouncy" moment; it is the only overshoot in the product.
3. **Open from origin:** dialogs, the palette and Android sheets scale from 0.96 plus fade, with `transform-origin` at the trigger where known.
4. **Lists stagger in** on the first load only: 24 ms step, capped at 8 rows, 8 px rise plus fade. Updates (a new notification, a progress tick) don't re-stagger.
5. **Status "breathing"** is the slow opacity pulse on the status dot (1.6 s). It runs only while the state is *connecting* or *mirroring starting*. Connected is still.
6. **Page change:** cross-fade plus a 6 px shift (desktop, `--dur-base`). On Android, the bottom-nav change is a fade-through.
7. Nothing loops unless it represents live work: indeterminate progress, connecting.

### 6.3 Reduced motion
- **Desktop:** `@media (prefers-reduced-motion: reduce)` and an in-app "Reduce motion" setting (`data-motion="reduce"`) both remove transforms, staggers, springs and loops. Colour and opacity changes stay, at `--dur-fast`, so state changes are still perceivable.
- **Android:** read `Settings.Global.ANIMATOR_DURATION_SCALE`; at 0, or when the in-app toggle is on, `LocalReducedMotion` is true. Infinite transitions are not started, springs become `snap()`, and fades are kept at 120 ms.

---

## 7. Iconography

**Desktop: the existing inline SVG sprite, one family.**
- 24 px grid, `stroke-linecap/linejoin: round`.
- Stroke weight by size: **16 px icons at 1.75, 20 px at 1.75, 24 px at 1.5, 40 px empty-state art at 1.25.**
- Size tokens: `--icon-sm: 16`, `--icon-md: 20`, `--icon-lg: 24`, `--icon-xl: 40`.

**Android: `Icons.Outlined` (material-icons-extended), one family.** The selected bottom-nav item uses `Icons.Filled` of the same glyph, which is the standard Material pattern and the same family. Sizes are the same tokens in dp.

**Shared glyph map** (the same concept uses the same glyph on both):

| Concept | Desktop sprite | Android |
|---|---|---|
| Connect | `i-link` *(new)* | `Outlined.Link` |
| Mirror | `i-cast` | `Outlined.Cast` |
| Files | `i-folder` | `Outlined.Folder` |
| Send file | `i-upload` | `Outlined.Upload` |
| Download | `i-download` | `Outlined.Download` |
| Clipboard | `i-clipboard` | `Outlined.ContentPaste` |
| Notifications | `i-bell` | `Outlined.Notifications` |
| Settings | `i-settings` | `Outlined.Settings` |
| Encrypted / pinned | `i-lock` *(new)* | `Outlined.Lock` |
| To phone / to PC | `i-arrow-right` *(new)* + device glyph | `Outlined.ArrowForward` + device glyph |
| Success | `i-check` | `Outlined.CheckCircle` |
| Warning | `i-alert` *(new)* | `Outlined.WarningAmber` |

**No emoji anywhere** (UI, tray, toasts), and no text arrows (➔ ⟷) as icons. An icon conveying status always has a text label or an accessible name.

---

## 8. Components

Every component has these states: **rest, hover (desktop), pressed, focus-visible, selected (if applicable), disabled, loading (if it triggers work).**
- **Disabled:** 45% opacity, no shadow, `cursor: not-allowed`, and never focusable for actions that can't apply. It keeps a tooltip explaining why ("Connect a phone first").
- **Loading:** a spinner replaces the leading icon, the label stays, and the width is locked, so there is no layout shift.

### 8.1 Buttons

| Variant | Use | Desktop | Android |
|---|---|---|---|
| Primary | The one main action per surface | accent bg, on-accent fg | `Button` with `primary` |
| Tonal (secondary) | Second actions | accent-subtle bg, accent-strong fg | `FilledTonalButton` |
| Ghost | Low emphasis, toolbars | transparent, text-2, hover surface-2 | `TextButton` |
| Outline | Neutral alternative inside cards | surface bg, 1 px border-strong | `OutlinedButton` |
| Danger | Destructive (Forget pairing, Clear history, Delete) | ghost or outline with danger fg; **filled danger only inside a confirmation dialog** | same |
| Icon | Toolbar | 32 px square, 20 icon, accessible name required | `IconButton`, 48 dp target, 24 icon |

- **Sizes, desktop:** sm 28, md 34 (default), lg 40.
- **Sizes, Android:** 40 dp visual inside a 48 dp target; primary full-width is 52 dp.
- Labels use sentence case and verbs: "Send file", "Start mirroring", "Scan QR code".

### 8.2 Inputs and search
- **Height:** 36 px desktop / 56 dp Android (M3 `OutlinedTextField`).
- **Colours:** border `--input-border` (≥ 3:1). Focus shows the accent border plus a 3 px `--accent-subtle` halo (desktop); Android uses the M3 focused colours mapped to accent.
- **Labels and errors:** every input has a visible label (not placeholder-only). Errors appear as a line under the field in danger, with an icon, and `aria-describedby`.
- **Numeric fields** (IP, port, code) use mono and `inputmode="numeric"`; Android uses `KeyboardType.Number`.
- **Search** is pill-shaped on desktop (`--r-pill`), with a leading search icon, a clear button when non-empty, and an `Esc` hint. Enter forces search; typing debounces at 250 ms (the current behaviour is kept).

### 8.3 Segmented control and tabs
- **Segmented** (2–4 options, same-page filter, e.g. Browse / Recent):
  - The track is `--surface-2`. The thumb is a `--surface` pill that **slides** using `--ease-spring`.
  - Roles: `role="radiogroup"` + `role="radio"`, with arrow keys to move between options.
- **Tabs** (panels with different content, e.g. Pair: QR code / Wireless debugging / USB):
  - A 2 px accent indicator that slides.
  - Roles: `role="tablist"`, `aria-selected`, `aria-controls`, arrow-key roving `tabindex`.

### 8.4 List rows
- **Heights:** single-line 40 desktop / 56 Android; two-line 52 / 72.
- **Anatomy:** leading 32 px icon tile, title (body, 1 line, ellipsis), subtitle (body-sm, text-2), trailing meta (caption, tabular) and trailing actions.
- **States:**
  - Hover: `--row-hover-bg`.
  - Selected: `--row-selected-bg` plus an inset 2 px accent bar.
  - Focus: the focus ring is inset, so it doesn't clip.
- Row actions appear on hover/focus on desktop but **stay in the layout** (opacity 0 → 1, never `display` toggled), and they're always visible on Android.

### 8.5 Cards
- **Solid content card:** `--surface`, `--r-lg`, 1 px `--border`, `--shadow-sm`, padding 16 (desktop) / 20 (Android).
- **Status cards** (Connect hero): solid, with an accent or success **hairline glow** on the top edge when connected (an inset gradient, not a border-width change).
- A card has **at most one primary button**.

### 8.6 Badges and pills
- **Status pill:** an 8 px dot plus a text label, in the semantic subtle bg and semantic fg. The dot alone never carries meaning.
- **Count badge:** accent bg, on-accent fg, min 18 px, tabular, "9+" cap.
- **Tag** (e.g. "Read-only", "Encrypted"): neutral `--surface-2`, text-2, `--r-xs`.

### 8.7 Toasts / snackbars
- **Desktop:** bottom-right, glass-strong, `--r-md`, max 360 px, icon + message + optional action ("Open", "Retry", "Undo").
  - Success/info stays 4 s with `role="status"`.
  - An error stays 7 s with `role="alert"` and a close button.
  - Hovering pauses the timer. At most 3 stacked; the oldest exits first.
- **Android:** M3 `Snackbar` with the same rules, replacing `Toast` for in-app results. A system `Toast` is kept only where no UI is visible.

### 8.8 Dialogs
- Glass-strong over the scrim, `--r-xl`, max-width 460 (narrow) / 620 (wide).
- **Behaviour:**
  - Focus is trapped and moves to the first field (or the primary button), and returns to the opener on close.
  - Esc closes. Enter confirms when the focus isn't in a multi-line field.
- **Actions sit bottom-right in this order:** ghost Cancel, then primary. Destructive dialogs use filled danger as the primary.
- **Android:** `AlertDialog` / `ModalBottomSheet` styled via theme. Sheets are used for multi-step flows (Pair).

### 8.9 Tooltips
- **Desktop:** appears on hover after 500 ms or immediately on keyboard focus. Caption text on glass-strong. Used for icon-only buttons and disabled reasons. Never holds essential information.
- **Android:** M3 `TooltipBox` on long-press for icon buttons.

### 8.10 Empty, loading and error blocks
- **Empty:** 40 px icon in a 64 px accent-subtle circle, a title (title), one line of body-sm, and **one action** (the recovery).
  - Example: "No notifications yet", "They'll appear here as your phone receives them.", [Turn on notification access].
- **Loading:** skeleton rows (3–6) in `--surface-2` with a 1.2 s shimmer; under reduced motion this is static. Never a lone spinner on a blank panel. Spinners appear only inside buttons and small inline status.
- **Error:** danger-subtle icon circle, a plain-language cause, and a recovery action ("Try again", "Reconnect", "Open settings"). Raw error strings go in a secondary line in mono, collapsible.
- **Offline / partial:** an inline banner at the top of the affected card. Example: "This connection can't browse the phone. Switch to the wireless debugging connection."

### 8.11 Progress and transfer rows
- **Anatomy:** a file-type icon tile, the name (ellipsis in the middle for long names), meta, a progress bar (4 px, `--r-pill`) and a trailing action.
  - Meta while running: size, %, direction.
  - Meta when done: "Sent to Download", "Saved to ~/Downloads/A+Connect".
- **Determinate when the event carries `received`/`total`.** The companion path already sends these on `event:transfer-progress`, and today's renderer drops them; using them is a UI-only change. Otherwise the bar is indeterminate, with the text "Sending…".
- **States:**
  - Queued: muted.
  - Active: accent bar, Cancel.
  - Done: success check, "Open" and "Show in folder".
  - Failed: danger, reason, "Retry".
  - Cancelled: muted, "Cancelled".
- **Optimistic:** the row appears the instant the user drops or picks a file, before `started` arrives, in the queued state.

### 8.12 Dropzone
- **Inline zone:** a 1.5 px dashed `--border-strong` outline, `--r-lg`, an upload icon, and two lines of copy. It's keyboard-operable (Enter/Space opens the picker) with `role="button"`.
- **Page-level drop:** dragging files anywhere over the Files page shows a full-page glass overlay ("Drop to send to {phone}"). The inline zone collapses to a compact "Send files" button once there is transfer history.

### 8.13 Command palette (desktop)
- **Opening:** Ctrl/Cmd+K, or `/` when no input is focused.
- **Layout:** a glass-strong panel, 560 px wide, top-centre at 18vh. A search input, then grouped results: Actions, Go to, Phone files (when search text is ≥ 2 characters and a phone is connected; reuses the existing search API).
- **Keyboard:** ↑/↓ moves, Enter runs, Esc closes, Tab is trapped. Each item shows its shortcut on the right.
- **Actions:**
  - Add device / Pair, Disconnect, Reconnect
  - Start / Stop mirroring
  - Send file…
  - Search phone files
  - Turn clipboard sync on/off
  - Turn phone connection on/off
  - Copy pairing code
  - Go to Connect / Files / Clipboard / Notifications / Settings
  - Toggle theme, Reduce transparency, Reduce motion
- **Implementation:** it calls only existing renderer functions and `window.api` methods, with no new IPC.

**Shortcuts:**

| Keys | Action |
|---|---|
| Ctrl+1…5 | Go to the pages in order |
| Ctrl+O | Send file |
| Ctrl+F | Focus file search (on Files) |
| Ctrl+, | Settings |
| Ctrl+Shift+M | Start/stop mirroring |
| Esc | Close the top overlay |

---

## 9. Information architecture

Each page owns everything about its subject. Nothing about connections lives on another page, and nothing about files lives on another page.

```text
Desktop sidebar                    Android bottom bar
---------------                    ------------------
[Connect]        Ctrl+1            [Connect]
[Files]          Ctrl+2            [Files]
[Clipboard]      Ctrl+3            [Clipboard]
[Notifications]  Ctrl+4            [Settings]
---------------                    (Notifications access + status live in Connect
[Settings]       Ctrl+,             on Android, since the phone is the source)
```

| Page | Contents (both clients unless marked) |
|---|---|
| **Connect** | <ul><li>Device hero: name, rename (Android), connection status, transport (Companion · encrypted / Wi-Fi debugging / USB), last seen</li><li>Telemetry: battery, Android version, storage free, system version (desktop)</li><li>**Mirror card:** start/stop, in-window view (companion), reason it stopped</li><li>**Pair:** QR code + typed code (desktop shows; Android scans or types), wireless debugging pair/connect and USB steps (desktop)</li><li>Phone connection on/off (kill switch), Reconnect, Disconnect, Forget pairing</li><li>Connection picker when both transports are live (desktop)</li><li>Background keep-alive and notification access permissions (Android)</li></ul> |
| **Files** | <ul><li>Send: drop or pick (desktop); File / Photos and videos (Android)</li><li>Phone browser: one component for both transports; browse, search, recent, multi-select, download, open, new folder, upload here, delete (ADB)</li><li>Transfers with progress and history</li><li>Where received files go (both sides)</li><li>"Let the computer browse this phone" permission (Android)</li><li>Storage free</li></ul> |
| **Clipboard** | <ul><li>Sync on/off with live status</li><li>Last synced</li><li>Send text…, Send current clipboard (Android), Send to phone clipboard (desktop)</li><li>History inline with Copy, Clear history</li><li>Keyboard setup (Android, one place only)</li><li>Desktop clipboard backend detail</li></ul> |
| **Notifications** (desktop) | Ongoing / Recent, dismiss, clear, empty state that names the phone-side permission |
| **Settings** | Appearance (theme, reduce transparency, reduce motion), Mirroring confirmation preference (desktop), device name (Android), About (one version string per client, from `package.json` / `versionName`) |

**Empty-state routing:** with no device connected, the desktop shows **Connect** in its empty state (pairing front and centre) instead of a separate "empty" view. Files, Clipboard and Notifications stay reachable and show their own offline empty states with a "Connect a phone" action. This replaces today's forced redirect to the empty view.

---

## 10. Screen specs

Each spec lists purpose, the one primary action, layout, states, and how each platform adapts it.

### 10.1 Connect
- **Purpose:** know at a glance whether the phone is reachable and how, and fix it if not.
- **Primary action:**
  - Disconnected: **Pair a phone** (desktop) / **Scan QR code** (Android).
  - Connected: **Start mirroring**.
- **Layout, desktop:**
  - Sticky glass page header: title, transport pill, overflow menu with Reconnect / Disconnect / Forget.
  - Hero card: phone illustration with a live status ring, name, transport line in mono for the IP.
  - Below that, a telemetry row (4 compact stats, tabular), then the Mirror card. The mirror canvas expands inside it.
  - Then the "Connection" section: kill switch row, encryption row, and pairing (collapsed when paired, expanded when not).
- **Layout, Android:** the same order as a scrolling column. The hero uses a phone ↔ PC graphic whose link line "breathes" only while connecting. Permission cards are listed at the bottom; granted ones collapse into a single "All set" row.
- **States:**

| State | Hero shows | Primary action |
|---|---|---|
| Never paired | Empty hero, pairing expanded | Pair (desktop) / Scan QR (Android) |
| Listening / waiting for phone | Pairing expanded, code + QR visible | — |
| Connecting | Warn pill "Connecting…", breathing dot, Cancel | — |
| Connected | Success pill, telemetry filled | Start mirroring |
| Connected without clipboard keyboard | Success pill + warn banner "Clipboard won't sync until A+ Connect Keyboard is chosen" | Set keyboard (Android) |
| Phone connection off (kill switch) | Neutral pill "Off" | Turn on |
| Error / not trusted | Danger banner with reason | Forget pairing and pair again |

- Mirror sub-states are taken from `describeMirror()` reasons and shown as a line plus a recovery action. Example: "Capture was not allowed on the phone" → [Try again].

### 10.2 Files
- **Purpose:** move files both ways and find anything on the phone.
- **Primary action:** **Send file**.
- **Layout, desktop:**
  - Sticky glass header: title, "To {phone} · {destination}" in mono, [Send file] primary.
  - Below it the phone browser card: a toolbar with Browse/Recent segmented control, search pill, breadcrumb (mono, clickable segments), Refresh, New folder, Upload here.
  - Then the virtualised-height list, with a floating glass **selection bar** (n selected · Download · Clear) docked to the bottom of the card.
  - Then the Transfers section, then "Received files go to" (path in mono · Open · Change…).
- **Layout, Android:** a 2-up "Send" grid (File, Photos and videos), the active transfer card, "Recent transfers", a "Received files" folder row, and a "Computer can browse this phone" permission row.
- **States:**
  - **Not connected:** empty state with [Connect a phone].
  - **Companion without browse permission:** explain it's read-only and that the permission is granted on the phone.
  - **Loading:** skeleton rows.
  - **Empty folder:** "This folder is empty".
  - **No results:** "Nothing matches '{q}'", with a [Search again from scratch] action, which is Refresh with `fresh=true`.
  - **Transfer failed:** a Retry action on the row.
- **Adaptation:** ADB connections get the full toolbar. The companion connection hides write actions (New folder, Upload here, Delete) and shows a "Read-only" tag. It's one browser component whose data source switches.

### 10.3 Clipboard
- **Purpose:** trust that copy and paste crosses devices, and recover past clips.
- **Primary action:** the **Sync** switch. Secondary: **Send text…**
- **Layout:**
  - A status card: switch, live pill (Syncing / Connecting / Off / Error) and a one-line reason.
  - "Last synced" preview with a direction label ("From phone" / "To phone", with icon and text).
  - History list: up to 3 lines per item, direction, time, Copy, and Clear history in the section header.
  - Android adds keyboard setup as the first card when not active.
- **States:**
  - Off: neutral.
  - Syncing: success.
  - Error: danger plus reason ("wl-paste not found" → plain language).
  - No keyboard (Android): warn banner with [Choose keyboard].
  - Empty history: "Copy something on either device to see it here."

### 10.4 Notifications (desktop)
- **Purpose:** read the phone's notifications without picking it up.
- **Primary action:** none; reading is the job. Secondary: Clear all.
- **Layout:** Ongoing section (compact rows, accent inset bar), then Recent (relative time, tabular). Row dismiss is on hover/focus.
- **States:**
  - Empty, connected: "No notifications yet. If this stays empty, turn on notification access in the phone app's Connect page."
  - Not connected: [Connect a phone].

### 10.5 Settings
- **Desktop:** a full page, not a modal, so it's reachable with Ctrl+,. Sections: Appearance, Mirroring, About.
- **Android:** a tab with Appearance (follows system / light / dark, reduce transparency), Device name, About.
- Per-feature settings live on their feature pages.

### 10.6 Overlays
- **Pair dialog (desktop):** tabs QR code (default) / Wireless debugging / USB. The QR sits on a white tile (the QR needs a white quiet zone, even in dark mode) with the code in mono-lg and the IP:port in mono. Wireless debugging uses labelled fields (IP address, Port, Pairing code) and inline result lines.
- **QR scanner (Android):**
  - Full-bleed camera with a dimmed surround and a rounded 260 dp cut-out with animated corner brackets (static under reduced motion).
  - A glass top bar with Close (48 dp) and a torch toggle if the device supports it (the CameraX `CameraControl` is already available).
  - A bottom glass hint, "Point at the QR code on your computer", plus [Enter code instead].
  - Haptic confirm on a successful scan.
- **Dialogs (Android):** Rename, Send text, Confirm forget, Confirm clear history. Clipboard history moves out of a dialog into the Clipboard tab.
- **Tray (desktop):**
  - Plain labels, no emoji.
  - Status line: "Connected · Reno 14 · 82%" / "Not connected" / "Phone connection off".
  - Items: Start mirroring / Stop mirroring, Open Files, Clipboard sync (checkbox), Open A+ Connect, Quit.
  - The tooltip is "A+ Connect".

---

## 11. Quality bar (acceptance criteria)

A screen is **done** only when every box is checked:

- [ ] **One primary action** per surface; everything else is tonal/ghost.
- [ ] **All states designed and reachable:** empty, loading (skeleton), error with recovery, offline/partial, success. Each has copy and one next action.
- [ ] **No dead clicks:** every control either acts, shows progress within 100 ms, or is disabled with a reason.
- [ ] **Optimistic feedback** for send/download (the row appears instantly).
- [ ] **Keyboard (desktop):**
  - Logical tab order and visible focus on every interactive element.
  - Esc closes the top overlay; Enter confirms.
  - Arrow keys work in tabs, segmented controls, lists and the palette.
  - The command palette covers the top actions.
- [ ] **Touch (Android):** every target ≥ 48 × 48 dp, and every switch row toggles from the whole row with merged semantics.
- [ ] **Screen readers:** every icon button has a name, statuses are text, and live regions announce transfer done/failed and connection changes.
- [ ] **Contrast measured:** body ≥ 4.5:1 and meaningful UI ≥ 3:1, in light and dark, including on glass.
- [ ] **Reduced motion and reduced transparency** both verified.
- [ ] **No layout shift** on hover, press, selection or loading.
- [ ] **Same concept, same name, same icon** on both clients (glossary in §12).
- [ ] **No hardcoded hex, no inline `style=""`, no emoji** in UI code.

---

## 12. Glossary (one name per concept)

| Use | Never |
|---|---|
| **Connect** (page), **Pair** (first-time setup) | Interconnect, Multi-Screen Connect, Add device (as a page) |
| **Mirror / Start mirroring** | Cast, Casting, Screen Cast, Multi-Screen |
| **Send file** | Send File, File Drop, Upload (except "Upload here" in a folder) |
| **Files** | File Transfer |
| **Clipboard / Clipboard sync** | Shared Clipboard, Companion TLS Sync |
| **Notifications** | — |
| **Phone connection** (the companion channel); **Encrypted** (the TLS tag) | Companion, Companion TLS, TLS 1.3 Pinned (as UI copy) |
| **Wireless debugging / USB debugging** (the ADB paths) | ADB (in UI copy) |
| **Forget pairing** | Reset PC Certificate Pairing |
| **Computer** in phone copy, **phone** in desktop copy | PC ➔ Phone, Linux PC |

---

## 13. Contract changes (additive only)

| Change | Why | Files | Status |
|---|---|---|---|
| `preload.js`: `onNavigate: (cb) => ipcRenderer.on('route:navigate', (_, view) => cb(view))`; renderer subscribes and calls `showView` | Tray "Open File Drop" already sends `route:navigate`, but nothing listens | `preload.js`, `renderer.js` | **Approved** |
| Android: `implementation("dev.chrisbanes.haze:haze-android:1.1.1")` | Backdrop blur for glass; M3 has none. 1.1.1 is the release built against Compose UI 1.7.5, so no Compose bump. | `app/build.gradle.kts` | **Approved** |

No IPC channel is renamed, no `window.api` signature changes and no protocol message changes. Anything further gets proposed here first.

---

## 14. Anti-patterns (specific to this product)

- Putting pairing, the kill switch or the destination folder on the Clipboard page.
- Green buttons. Green is status.
- Status shown by colour alone (dots, coloured text) without a label.
- "CONNECTING" shown when the app isn't trying, or any status that lies.
- Emoji or text arrows as icons, especially in the tray.
- Glass on scrolling content, glass on glass on glass, or muted text on glass that content scrolls under.
- A spinner on a blank panel instead of skeleton rows.
- A Toast as the only evidence that something happened.
- Inline `style=""`, hardcoded `#fff` / `rgba()` in markup or Kotlin call sites.
- Passing six colour parameters into every composable instead of `LocalAplusColors`.
- Duplicated controls (keyboard permission in two places, clipboard status in Settings).
- Lifting cards on hover with `translateY` plus a shadow jump; or border-width changes on selection.
- Infinite animations for steady states.
- Two file browsers with separate markup for the same job.
- Hardcoded values that the app already knows (port `24443`, `/sdcard/Download`, version `1.0.0`).
