# Step status — what is applied, and the one gap

Snapshot taken 14 Nov 2026, 23:05 IST, from the working tree of
`github.com/asifahamed-ece/A-Connect`.

## Applied and present in this tree

| Step | Scope | Where |
|---|---|---|
| 1 | Audit of desktop + Android UI | `docs/design/01-audit.md` |
| 2 | Design system, IA, component specs | `docs/design/DESIGN.md` |
| 3 | Desktop component layer | committed (`353bd15`) |
| 3.1 | Desktop tweaks: Clipboard cleanup, destination moved to Settings, real PC name, Settings controls | committed (`353bd15`) |
| 3.2 | Desktop avatar upload | committed (`353bd15`) |
| 4 | Android component layer: tokens, `AplusTheme`, components, 4-tab glass bar, screens | applied, uncommitted |
| 4.1 | Floating glass bar, glass switches and slider, live data link | applied, uncommitted |
| 4.2.1 | `LinkSchedule` frame-clock packets, live animator-scale watching | applied, uncommitted |
| 4.2.2 | Desktop glass + History tab + QR toggle; Android History rebuilt from source | applied, uncommitted — see `STEP4.2.2.md` |

`docs/design/README.md` indexes the documents and records the corrections to the
audit.

## The gap (closed in 4.2.2): step 4.2 was not in this tree

> Step 4.2.2 rewrites the History screen in source, matching the description
> below. Building this tree no longer removes History. The original 10 step-4.2
> tests were not recovered; `FileHistoryTest` (4 tests) covers the new list rules.

Step 4.2 added a **file History screen**. Its source was never delivered here —
only the built APK. Proof by diffing class lists between the step 4.2.1 APK and
a local build:

```
present in the APK, absent from this source tree:
  ui/HistoryScreenKt        ui/HistoryUi        ui/HistoryActions
  data/FileHistory          data/SharedFileEntry
  ui/components/Segment
```

What the shipped APK does, for whoever rebuilds it:

- Files tab has a **History** button at the top right (clock icon, outlined pill).
- It opens a History page: back arrow, "Recent files shared between this phone
  and the computer. Tap one to open it.", a **Sent / Received** segmented toggle
  with counts, an **Open folder** button, and an empty state ("Nothing received
  yet" / "Files the computer sends to this phone show up here.").
- Backing store: `FileHistory` + `SharedFileEntry` (`Direction` = sent/received),
  so the list survives restarts.
- `ui/components/Segment` is the two-option segmented control used by the toggle.

## Consequences right now

- **The phone runs step 4.2 + 4.2.1.** Installed APK:
  `aplus-connect-step4.2.1-debug.apk`, package `com.aplusconnect.companion`,
  versionName `0.1.0`.
- **This tree is step 4 + 4.1 + 4.2.1.** It compiles and passes **142 unit
  tests** (137 from step 4.1 + 5 new `LinkScheduleTest`).
- STEP4.2.1.md claims **152** tests. The missing 10 belong to step 4.2. That gap
  is the measurement of what is absent.
- **Building this tree and installing it removes the History screen.** Do not
  install a local build until step 4.2 is recovered or rewritten.

## How to verify the tree

```bash
cd companion-android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

JDK 17 is required: Gradle 8.9 / AGP 8.7.3 do not accept the system default
JDK 27.

## Next

Either recover `step4.2-android.patch`, or have the History screen rewritten
against this tree — `FileHistory`, `SharedFileEntry`, `Segment`, `HistoryScreen`,
and the History button on the Files header — so that source and the installed
APK agree again. Only then commit, so the repo does not record a half-chain.
