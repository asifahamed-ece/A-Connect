# The hand-written keyboard (parked)

This is the clipboard keyboard that was built into the app on 2026-11-12, and
then retired the same day: it worked (suggestions, emoji panel, a resizable
clipboard strip, five themes, number row, undo), but it was written from
scratch in a few hours and it showed. It is not a keyboard anyone can use as a
daily driver, and a keyboard people type on every day is not the right thing to
keep hand-maintaining.

## Why a keyboard at all

Android grants background clipboard access to the **default keyboard** and
nothing else: `ClipboardService.clipboardAccessAllowed()` calls
`isDefaultIme()`, which reads `Settings.Secure.DEFAULT_INPUT_METHOD`. An
accessibility service has the same access, but any enabled accessibility
service makes Google Wallet refuse contactless payments, so the keyboard role
is the only route that keeps payments working.

That also means the role cannot be handed off: switching to Gboard writes
`DEFAULT_INPUT_METHOD` (`InputMethodManagerService.setInputMethodLocked()` →
`InputMethodSelector.putSelectedInputMethod()`), so a keyboard that "holds the
role and delegates typing" loses the privilege the moment it delegates. The
keyboard the user types on has to be the one with the privilege.

## What replaced it

A fork of an existing open-source keyboard (HeliBoard), patched so that the
clipboard it reads is forwarded to this app. See `docs/companion/` for the
fork notes.

## Files

- `kotlin/` — the eight source files, exactly as they were
- `res/method.xml` — the input-method declaration that pointed at the service
- `words.txt` — the 9,894-word suggestion list, ordered by frequency
  (from first20hours/google-10000-english, no-swears list)

To revive it: copy `kotlin/*.kt` back to
`app/src/main/kotlin/com/aplusconnect/companion/ui/ime/`, copy
`res/method.xml` back to `app/src/main/res/xml/`, copy `words.txt` back to
`app/src/main/assets/`, and re-add the `<service>` block to the manifest (the
git history of `AndroidManifest.xml` has it verbatim).
