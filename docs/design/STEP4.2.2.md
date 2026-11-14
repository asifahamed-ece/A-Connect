# Step 4.2.2 — glass, History, QR toggle

## Desktop

- **Frosted glass everywhere.** Window, sidebar, cards, inputs and toasts are
  translucent with background blur and a soft inner highlight, over a slowly
  drifting colour wash. Settings → Appearance → *Reduce transparency* turns it
  back to solid surfaces (also follows the system setting).
- **History page**, opened from the History button on Files (same as the
  phone): Sent / Received toggle with counts, newest first, click a row to open
  the file, *Open folder*, *Clear*. Kept across restarts (renderer storage,
  200 items). Check: `node scripts/check-file-history.js`.
- **Files no longer lists finished transfers.** It shows only what is sending
  now or failed (with Retry / Dismiss). Finished items move to History, so each
  file appears once.
- **Connect → Pairing QR has Hide QR / Show QR.** The choice is remembered.
- **Tray / taskbar icon** uses the A+ mark on a transparent background; the
  window uses the full app icon. `scripts/install-desktop-entry.sh` installs a
  launcher so the taskbar shows the A+ icon instead of a generic one.

## Android

- **History rebuilt in source** (the gap in STEP-STATUS.md): `data/FileHistory`
  + `SharedFileEntry`, `ui/components/Segment`, `ui/HistoryScreen`.
- Files header gets a **History** button; the old Transfers list is gone —
  only in-flight or failed sends stay on Files.
- Received files are recorded by `CompanionService`; finished sends by
  `MainActivity`. Tap opens the file (FileProvider for the app folder);
  *Open folder* opens the chosen folder, else Downloads.
- Tests: `FileHistoryTest` (4).

## Not verified here

- Android: only `FileHistoryTest` was run (plain JVM). The Compose screens were
  not compiled — run `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Desktop: the UI was checked in a browser with a stubbed bridge, not inside
  Electron with a real phone.
