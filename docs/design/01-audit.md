# A+ Connect: Step 1 audit

Source: github.com/asifahamed-ece/A-Connect (default branch, read 14 Nov 2026).
Scope: desktop renderer (`src/index.html`, `src/style.css`, `src/renderer.js`, tray in `main.js`) and Android UI (`MainActivity.kt`, `ui/*.kt`, `qr/QrScannerView.kt`).
No code was changed in this step.

Severity: **High** = breaks accessibility, misleads the user, or is a functional bug. **Medium** = inconsistent or hard to scan. **Low** = polish.

---

## 1. Screen inventory (today)

### Desktop
| Surface | What lives there now |
|---|---|
| Sidebar | Profile ("This PC"), device list (ADB row + companion row), Add device, nav: Multi-Screen Connect, Files, Notifications, Clipboard, Settings |
| Empty | Illustration, "No devices found", Add device |
| Device | Title, transport chip, Disconnect, companion note, 4 telemetry tiles, Cast card (+ in-window canvas for companion mirror), 4 shortcut tiles |
| Files | Dropzone, multi-select bar, **two separate browsers** (companion read-only browser, ADB browser with Browse/Recent, search, New folder, Upload here), transfer list |
| Notifications | Ongoing / Recent sections, Clear, per-row dismiss |
| Clipboard | Sync toggle + status pill, **Companion TLS card (kill switch, Show QR, pairing code)**, **pairing QR card**, **"Send a file to the phone" card**, **"Save received files to" card**, clipboard history |
| Modals | Add device (Pair by QR / Wireless debugging / USB), Cast confirm, Text prompt (New folder), Settings |
| Tray | Device label, Start/Stop mirroring, Open File Drop, Clipboard Sync checkbox, Open, Quit |
| Toasts | One style, 3.2 s, ok / err |

### Android
| Surface | What lives there now |
|---|---|
| Top bar (all tabs) | Title + "ColorOS" chip + "Seamless PC Interconnection", History button, Reconnect button |
| Interconnect | Hero connection card (status, TLS chip, phone ↔ PC graphic, rename), Companion kill switch, Shared Clipboard toggle + last synced, Keyboard permission, Battery keep-alive |
| Send File | File / Media / Paste / Text grid, progress card, Target PC card |
| Settings | Device name, Network & pairing (Scan QR, manual host/port/code, Save & Reconnect, Reset pairing), PC destination (static text), Save received files to, Files the PC can browse (all-files access), Stored clips + Clear all, Keyboard access (duplicate) |
| Overlays | QR scanner, Rename dialog, Send text dialog, Clipboard history dialog |

---

## 2. Grouping problems (your request: related things on one page)

| # | Sev | Finding |
|---|---|---|
| G1 | High | **Desktop: connection and pairing controls are split across 3 places.** Companion on/off, pairing code and "Show QR" are on the **Clipboard** page; Add device is a modal; Disconnect and transport are on the Device page. |
| G2 | High | **Desktop: file things are split.** "Send a file to the phone" and "Save received files to" are on the **Clipboard** page, not Files. Settings shows a hardcoded "/sdcard/Download". |
| G3 | High | **Android: connection is split across Interconnect, Settings and the top bar.** Status and kill switch are on Interconnect, pairing (Scan QR, manual entry, reset) is on Settings, Reconnect is in the top bar. |
| G4 | High | **Android: file things are split.** Sending is on Send File; where received files go and whether the PC can browse the phone are on Settings. |
| G5 | Medium | **Android: clipboard is spread over 4 places.** Toggle on Interconnect, Paste/Text on Send File, history behind a top-bar icon, Clear on Settings. The keyboard permission is shown twice, with different wording. |
| G6 | Medium | **Desktop: clipboard status is duplicated** in the Settings modal with a second pill. |

**Proposed grouping, the same on both apps:**

| Page | Desktop (sidebar) | Android (bottom bar) | Contents |
|---|---|---|---|
| **Connect** | 1st | 1st | Status and device identity, telemetry, Pair (QR / code / wireless debugging / USB), companion on/off, reconnect, disconnect, forget pairing, battery keep-alive, which connection is active |
| **Mirror** | 2nd | inside Connect, as a card (the phone is the screen being cast) | Start/stop casting, in-window view, the reason it stopped |
| **Files** | 3rd | 2nd | Send (drop or pick file/media), phone browser, search, recent, multi-select, transfers + progress, where received files go, "PC can browse this phone" permission, storage free |
| **Clipboard** | 4th | 3rd | Sync on/off, live status, last synced, send text / send current clipboard, history (inline, not a dialog), clear, keyboard permission (once) |
| **Notifications** | 5th | permission card on Connect (see A9) | Mirrored list, ongoing / recent, clear |
| **Settings** | footer | 4th | Appearance (theme, reduce transparency, reduce motion), device name, about, version |

All capabilities stay; only where they live changes. No IPC channel, `window.api` method or protocol message changes.

---

## 3. Accessibility (measured)

Contrast ratios computed with the WCAG formula from the current hex values.

| # | Sev | Finding | Measured |
|---|---|---|---|
| A1 | High | Desktop light `--text-3` (#94a3b8) is used for body-size text: row subtitles, empty states, telemetry labels, breadcrumb hints | **2.56:1** on surface, **2.39:1** on bg (needs 4.5) |
| A2 | High | Android: white text on the green primary (Scan pairing QR, Save, Send, nav selection) | **2.31:1** (needs 4.5) |
| A3 | High | Android: green used as text on white (status, "ACTIVE", percentages) | **2.31:1** |
| A4 | High | Android: warning orange as text ("CONNECTING", "OFFLINE") | **2.07:1** |
| A5 | High | Android muted text: light #98A2B3 **2.58:1**, dark #5B6373 **2.89:1** | needs 4.5 |
| A6 | High | Desktop dark: white on `--brand` #4c8dff (primary buttons, nav badges) | **3.20:1** |
| A7 | Medium | Borders that mark inputs and cards are below 3:1 in both apps (desktop `--border-strong` 1.40:1, Android border 1.24:1). Inputs must hit 3:1. | |
| A8 | High | Android touch targets under 48dp: top-bar buttons 38dp, rename edit icon 11dp, "SET/ALLOW" pills about 30dp tall | |
| A9 | High | Android has **no notification-access card**, yet desktop Notifications and media control depend on it (`CompanionService.kt:251`). A user cannot find out why notifications are empty. | |
| A10 | High | Android switches have no accessible name: TalkBack reads "Switch, on" with no label, because the row is not `toggleable` and has no merged semantics. | |
| A11 | Medium | Android `DeviceNode` is clickable (rename) with no role or label; the Edit icon is the only hint. | |
| A12 | Medium | Desktop modals do not trap focus or return focus to the opener. The Add device tabs lack `aria-controls` / `aria-selected`, and arrow keys do nothing. | |
| A13 | Medium | Desktop: the clipboard status dot has only a `title`, so its state is invisible to screen readers. | |
| A14 | Medium | Android hero pulse animation runs forever and ignores the system animation scale. Desktop reduced motion is handled globally (good), but the indeterminate bar becomes a static stripe with no label. | |
| A15 | Low | Desktop toasts use `aria-live="polite"` for errors too; errors should be `assertive` (role="alert"). | |

---

## 4. Consistency and identity

| # | Sev | Finding |
|---|---|---|
| C1 | High | Two accents: desktop blue `#2563eb`, Android green `#00C37B`. Android also uses blue for permission buttons, so one screen has two accents. |
| C2 | Medium | Different names for the same thing: "Multi-Screen Connect" / "Cast phone screen" / "Start Screen Mirroring" / mirror; "Send File" / "Send files" / "File Drop"; "Interconnect" / "Companion" / "Companion TLS Sync". |
| C3 | Medium | Platform branding in the UI: "ColorOS" chip in the Android top bar, "ColorOS" telemetry label with a clock icon, "OPPO Device Companion" tray tooltip. |
| C4 | Medium | **Emoji used as icons:** 🔒 in the desktop companion badge; 📱 📴 ⏹ 🖥 📂 📋 in the tray menu; ➔ ⟷ arrows in Android clipboard direction chips. |
| C5 | Medium | Two icon families: a custom 24px stroke sprite on desktop, Material *Filled* icons on Android. Proposal: Material Symbols **Rounded, outlined** on Android, with the desktop sprite redrawn to the same outline and corner style. |
| C6 | Medium | Shouting labels: ALL-CAPS 11sp "SET", "ALLOW", "ACTIVE", "READY", "OFFLINE", "CONNECTED". |
| C7 | Low | Versions disagree: desktop 1.0.0, Android 0.1.0, and Settings reads "A+ Connect 1.0.0 · Electron · scrcpy over ADB". |

---

## 5. Structure and code hygiene (UI layer only)

| # | Sev | Finding |
|---|---|---|
| S1 | Medium | Desktop `index.html` has about 20 inline `style=""` attributes on the companion, QR and destination cards, including hardcoded `#fff` and `rgba(255,255,255,0.06)`, which is invisible in light mode. |
| S2 | Medium | Desktop has two near-duplicate file browsers (companion and ADB) with separate markup, search fields and empty states. One component with two data sources would remove the drift. |
| S3 | Medium | Desktop buttons: `.btn-primary` and `.btn-ghost` exist, but the markup also uses `.btn .btn-secondary` with **no CSS rule for `.btn-secondary`**, so those buttons fall back to inline styles. |
| S4 | Medium | Android: every tab takes 6 colour parameters (`surfaceColor … textMuted`) instead of reading a `CompositionLocal`. The Material `ColorScheme` sets only 6 of about 30 roles, so stock components (dialogs, switches, text fields) pick default purple-ish tones. |
| S5 | Low | Desktop animates `transform: translateY(-2px)` on tile hover and `translateY(1px)` on press. These are transforms, so they don't shift layout, but the hover lift together with a shadow change reads as busy. |
| S6 | Low | Android uses Toasts for most feedback (sent, failed, copied). Results should appear in the UI next to the action, with a Snackbar for undo or retry. |

---

## 6. Functional bugs found while auditing (UI-side, will flag, not silently fix)

| # | Sev | Finding | Fix size |
|---|---|---|---|
| B1 | High | Tray **"Open File Drop"** sends `route:navigate`, but `preload.js` exposes no listener and the renderer never subscribes. The click opens the window and stays on the current page. | Additive: one `onNavigate` method in preload plus a renderer subscription. Flagged for approval. |
| B2 | High | Android hero shows **"CONNECTING"** in orange when it is actually disconnected and not trying (both branches of the `else` say connecting). | UI-only. |
| B3 | Medium | Android Target PC card hardcodes port `:24443` instead of `settings.port`. | UI-only. |
| B4 | Medium | Android "Save & Reconnect" changes colour based on connection state, so the primary action looks different from one moment to the next. | UI-only. |
| B5 | Low | Desktop `.cast-canvas` uses `var(--radius-md, 12px)`, a token that doesn't exist (the falsy fallback hides it). | UI-only. |

---

## 7. Feasibility notes for your style requests

**Liquid glass, desktop (Electron on KDE).** In-window glass works well: `backdrop-filter` blur and saturation over a soft ambient gradient, applied to the titlebar, sidebar, modals, toasts, command palette and the floating selection bar. See-through to the KDE desktop needs a transparent window, which is a `main.js` change and depends on KWin's blur. I'd keep that out of scope unless you want it.
**Guardrails:**
- Each glass layer keeps a minimum tint (about 72% light, 64% dark), and text contrast is measured against the worst case behind it.
- Blur is used only on chrome, never on long lists, to protect frame rate.
- A "Reduce transparency" toggle in Settings swaps glass for solid surfaces. This is stored in the renderer only, with no new IPC.

**Liquid glass, Android (Compose).** Material 3 has no backdrop blur, and `Modifier.blur` only blurs its own content. For true glass I'd add **Haze** (`dev.chrisbanes.haze`) because it's the maintained Compose backdrop-blur library. On API 26–30 it falls back to a tinted translucent surface automatically. This is a new dependency, so I need your OK.

**ColorOS 17-style motion.** This means spring-based, physical motion:
- Buttons and cards press down slightly (scale 0.97 through `graphicsLayer`, so nothing re-flows).
- Panels open from the point you tapped.
- List rows fade and rise in a short stagger.
- The connection status "breathes" only while it is actually connecting.

All of it is driven by shared duration, easing and spring tokens, and turns off with the system's reduce-motion setting on both apps.

---

## 8. Accent decision (to confirm in Step 2)

**Proposed: one blue accent for both apps, tuned per theme for contrast.** Green becomes the *success / connected* colour only.

Why:
- **Meaning.** In this product "connected" is the most important status. If the accent is also green, every primary button looks like a status, and connected vs. clickable blur together.
- **Contrast.** The current green can't carry white text (2.31:1) without darkening it into a murky teal. Blue reaches 4.5:1+ with white text in light mode and with dark text in dark mode.
- **Less churn.** The desktop is already blue, so only Android's accent moves.
- **ColorOS 17 feel.** Its system accent is blue, and glass reads cleaner over cool neutrals.

---

## Next step (Step 2)
Write `docs/DESIGN.md` with the identity decision, tokens (primitive → semantic → component), type, spacing, radius, elevation and glass, motion, icons, component specs and anti-patterns. No component code changes until you've reviewed it.
