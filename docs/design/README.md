# A+ Connect — redesign documents

The design record for the A+ Connect UI overhaul. These are the decisions and
the findings that the current code implements, and the brief for the work that
remains.

| File | What it is |
|---|---|
| [`01-audit.md`](01-audit.md) | Step 1 audit of the desktop renderer and the Android UI. Findings are numbered `A1`–`A15` (accessibility), `C1`–`C7` (consistency and identity), `S1`–`S6` (code hygiene), `B1`–`B5` (functional bugs), `G1`–`G6` (grouping). |
| [`DESIGN.md`](DESIGN.md) | The design system and information architecture. Authority for the accent, token layers, typography, spacing, radius and elevation, glass rules, motion, icons, component specs, IA, screen specs, quality bar, glossary, contract rules and anti-patterns. |
| [`STEP3.md`](STEP3.md) | The desktop component layer: what changed, which audit findings it closes, the one bug it fixed, and how to verify it. |
| [`design-preview.png`](design-preview.png) | **The target**, not the current UI: a mockup of the finished Connect page in light and dark. Its sidebar already reads Connect / Files / Clipboard / Notifications / Settings, with Mirror as a card inside Connect. This is what Step 5 builds toward. |
| [`screenshots/`](screenshots/) | The desktop at the end of Step 3, light and dark — the component layer applied. The sidebar still reads "Multi-Screen Connect", so these show the **pre-Step-5** nav. |

## Provenance

Step 1 to Step 3 were produced with Lovable against this repository. The
documents are committed here so the design record lives with the code, and so
any tool — a new Lovable account, a different agent, a new contributor — can
pick it up without a link that expires.

The repo is **ahead** of these documents. Steps 3, 3.1 and 3.2 are merged into
the default branch:

- `353bd15` — desktop component layer (tokens, glass chrome, buttons, toasts,
  dialogs, focus trapping, status pills, icon sprite, reduced motion and
  reduced transparency).
- The same commit carries 3.1 (Clipboard page cleanup, "Save received files to"
  moved into Settings, sidebar shows the real PC name and initials, Settings got
  real controls) and 3.2 (avatar upload, cropped to a circle, persisted across
  restart).

Do not re-apply them. `STEP3.md` describes what to expect; the branch shows
what is actually there.

## Corrections to the audit

Verified against the code on 14 Nov 2026. The audit text is left unchanged so
its finding numbers stay stable; read these alongside it.

| Finding | Correction |
|---|---|
| `C4` | There is no lock emoji in the companion badge — it reads `Code: …`. The tray emoji (📱 📴 ⏹ 🖥 📂 📋) and the Android `➔ ⟷` chips are real and still need replacing. |
| `A14` | The infinite pulse is real (`interconnect.kt:318`). "Ignores the system animation scale" is **unproven** — Compose 1.7 honours `LocalMotionDurationScale`. Verify on a device; do not assert it. |
| `B4` | Low, not Medium. The two values are `ColorOSGreen` and `ColorOSGreenDark`: the same hue, adjacent shades. |
| `S1` | 22 inline `style=""` attributes, not "about 20". All 22 are gone from the shipped code. |
| `S3` | `.btn-secondary` still had no CSS rule. It now exists, and the 4 uses are styled. |

One thing the audit missed: `scripts/ensure-deps.js` checks five modules but
**not `qrcode`**, which `package.json` declares and QR pairing needs.

## Locked decisions

Carried by `DESIGN.md` and already true in the code.

- **One blue accent** for both apps, tuned per theme for contrast. Green means
  success and connected, nothing else.
- **Page grouping** — desktop sidebar: Connect, Files, Clipboard, Notifications,
  Settings (footer). Android bottom bar: Connect, Files, Clipboard, Settings.
  Mirror is a card inside Connect on both clients; notification access is a
  permission card on Android's Connect page.
- **"Liquid glass, quietly"** — blur on chrome only, never on long lists. Each
  glass layer keeps a minimum tint. Reduce transparency swaps glass for solid.
- **One name per concept** (`DESIGN.md` §12). No emoji as icons. No inline
  styles. Body text ≥ 4.5:1, UI borders ≥ 3:1, Android touch targets ≥ 48dp.

## Remaining stages

| Stage | Scope |
|---|---|
| 4 | Android component layer: the same design system in Compose — theme tokens, glass via Haze, shared components. |
| 5 | Screens, rebuilt in pairs across both apps, per `DESIGN.md` §10. |
| 6 | Motion: ColorOS 17-inspired springs, Ctrl+K command palette on desktop, transitions and haptics on Android. |
| 7 | Accessibility pass: measured contrast, focus, touch targets. |
| 8 | Final polish. |
