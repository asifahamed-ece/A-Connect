# Step 3: Desktop component layer

Against github.com/asifahamed-ece/A-Connect (default branch, 14 Nov 2026).
Apply from the repo root: `patch -p1 < step3-desktop.patch`. Alternatively, `git apply step3-desktop.patch`, or copy the files in `files/` over the originals.

## Files changed
| File | What changed | Why |
|---|---|---|
| `src/style.css` | Rewritten into 3 token layers (primitive → semantic → component) plus a component layer. Existing token names are kept (`--bg --surface --text-2 --brand --r-* --s-* --dur` …), new ones sit beside them. Adds glass chrome (titlebar, sidebar, dialogs, toasts, selection bar) over an ambient canvas; `.btn` variants (primary, secondary/tonal, ghost, outline, danger, sm/lg, loading); status pills with dot + word; banner, empty block, skeleton, tag, kbd, code badge, QR tile; motion tokens with spring curves; press feedback by transform only; reduced motion (system + `data-motion="reduce"`); reduced transparency (system + `data-transparency="reduce"`). | DESIGN.md §2–8; audit S1, S3, S5, A1, A6, A7 |
| `src/index.html` | All 20 inline `style=""` removed and replaced by component classes. Brand mark uses tokens (no `#2563EB`/`#fff`). New icons `i-lock`, `i-link`, `i-alert`, `i-arrow-right`. Labels on pairing inputs, `inputmode="numeric"` on ports/codes, tab/tabpanel roles, a name for the clipboard status dot. | Audit S1, A12, A13; no emoji |
| `src/renderer.js` | <ul><li>Toasts: `role="alert"` for errors, 4 s / 7 s, hover pauses, max 3, exit animation.</li><li>Show/hide uses `hidden` instead of `style.display`.</li><li>🔒 badges replaced by the lock icon; "TLS Pinned" now reads "Encrypted".</li><li>**Bug fix:** `refreshPairingQr()` moved to top level (see below).</li><li>Transfer rows show real progress from `received`/`total`, plus done/failed styling.</li><li>Dialogs trap Tab and return focus to the opener.</li><li>Pair tabs: `aria-selected`, `aria-controls`, Arrow/Home/End.</li><li>Clipboard dot gets an accessible name.</li><li>Subscribes to the tray's navigation event.</li></ul> | Audit A12–A15, C4, B1; DESIGN.md §8.7, §8.8, §8.11 |
| `preload.js` | **Additive only:** `onNavigate(cb)` listens on the existing `route:navigate` channel. | Fixes tray "Open File Drop" (approved). No channel renamed, no signature changed. |

## Bug found and fixed (please confirm you're happy with it)
`applyCompanionInfo()` called `refreshPairingQr()` when the desktop started listening. But that function was declared *inside* `initNavigation()`, so the call threw a ReferenceError. That stopped the rest of the update: the status text and the code badge never filled in. It's now a top-level function, and it also fills the QR image on the Clipboard page, which used to show an empty box.

## Unchanged on purpose (later steps)
- **Page grouping** (Connect / Files / Clipboard …) and new copy: step 5. Labels like "Multi-Screen Connect" and "Cast" are still the old wording.
- **Ctrl+K palette and shortcuts:** step 6.
- **Skeleton rows** instead of "Loading…": the classes exist; the screens start using them in step 5.
- **Icon-only buttons in renderer rows** still rely on `title`; `aria-label` comes in the accessibility pass (step 7).
- `main.js`, services, IPC and protocol: untouched. The tray emoji labels live in `main.js` and change in step 5 with the tray states.

## How I verified
- `node --check` passes for `src/renderer.js`, `preload.js` and `src/format.js`.
- Whitespace: no trailing whitespace in the changed files (a stand-in for `git diff --check`, which I can't run here). The patch applies cleanly to a fresh copy (`patch --dry-run`).
- Walked every screen in Chromium with a stand-in `window.api` (a fake phone), in light and dark:
  - empty state, Pair dialog (all 3 tabs), device, Files with rows, selection and transfers (running at 63%, sent, failed), Notifications, Clipboard with the companion listening, Settings.
  - **0 console errors**. The listening state used to throw the ReferenceError above.
- Keyboard: → on the Pair tabs moves the selection (`aria-selected` = false,true,false). Esc closes the dialog and focus returns to the Add device button.
- The tray event `route:navigate 'files'` now lands on Files.
- With reduced motion on, the switch thumb stays in place (16 px), the status-dot pulse stops, and colour changes remain.
- Contrast: every token pair is measured in DESIGN.md §2.2.

## What you should run on your machine
```bash
patch -p1 < step3-desktop.patch
git diff --check
node --check src/renderer.js preload.js
npm start
```
Then:
- Walk the screens with your phone.
- Tray → Open File Drop should land on Files.
- In KDE, turn on Settings → Accessibility → Reduce animations (or `prefers-reduced-motion`) and check that nothing slides.
