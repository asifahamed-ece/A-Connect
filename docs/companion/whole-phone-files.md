# Browsing the whole phone

The PC can browse and search every folder on the phone, and copy files off it.
It cannot write, delete, rename or move anything, and it never needs USB
debugging, wireless debugging or developer options.

## Why not the system folder picker

The obvious design is the Android folder picker: the user shares a folder, the
app keeps a read grant for it, and the PC browses that tree. It cannot reach
what people actually want.

Since Android 11 the system picker refuses to grant:

- the storage root (`/sdcard`),
- the SD card root,
- `Download`,
- `Android/data` and `Android/obb`.

So "let my PC see my Downloads" - the single most useful case - is impossible
with the picker. That is a platform rule, not a bug to work around.

## What is used instead

Android provides a different permission for exactly this job:
**all files access** (`MANAGE_EXTERNAL_STORAGE`). It is what file managers and
on-device file search use. Two things make it safe to ship here:

- It is **not an accessibility service**. Google Wallet refuses NFC payments
  whenever any accessibility service is enabled (see
  `payment-block-findings.md`); this permission does not appear in that list, so
  payments keep working.
- It has **no runtime dialog**, by design. The user grants it once on a system
  Settings page - the app can only deep-link there.

The app therefore shows one card in Settings, "Files the PC can browse", with
its granted/not-granted state and a button that opens that system page. The
state is re-read whenever the app returns to the foreground, because the user
grants it outside the app.

Under all-files access the app still cannot read `Android/data` or
`Android/obb` - those are owned by other apps even for a file manager - so both
are skipped everywhere.

## What the PC can actually do

Read only. Concretely:

| Action | Supported |
| --- | --- |
| List any folder | yes |
| Search every folder by name | yes |
| Copy a file to the PC | yes |
| Upload into any folder | no |
| Delete, rename, move | no |
| Create a folder | no |

This is not a policy that could be relaxed later by accident: `PhoneFileBrowser`
and `StorageSearch` are guarded by a source test that fails the build if either
ever mentions a write API (`delete`, `deleteRecursively`, `renameTo`,
`createNewFile`, `mkdir`, `mkdirs`, `FileOutputStream`, `openOutputStream`,
`setWritable`, `setLastModified`).

## How a path works

Paths on the wire are **relative to the storage root**:

```
""                        the root of /sdcard
"Download"                the Download folder
"Download/books/Dune.epub"  one file
```

Relative paths are what make the read-only promise enforceable. The PC cannot
name a path outside the phone's shared storage, because it never gets to write
an absolute one. `PhoneFileBrowser.within()` is the single gate:

1. a leading `/` is stripped rather than honoured, so an absolute path is read
   as relative to the root;
2. any `..` segment is refused outright rather than normalised away;
3. the result is canonicalised and must still be under the canonical root, so a
   symlink pointing outside is refused too.

A refused path answers with an empty listing, never an error, so the desktop
cannot use failures to probe what exists outside the sandbox.

## How search works

Searching the whole phone is the expensive part, so it is designed around where
the time goes.

Walking `/sdcard` is slow on Android: the storage is FUSE, and each entry costs
a stat. On the test phone (OPPO Reno 14, ColorOS, Android 16) the first full
walk of 34,406 entries takes **5.8 seconds** (measured: the walk starts at the
moment the PC connects and logs `indexed 34406 entries` 5.8 s later); a naive
walk per keystroke would make the search box unusable.

So `StorageSearch` builds a **name index once** and filters it in memory:

- **Warmed on connect.** The walk starts as soon as the PC connects, not on the
  first keystroke, so a search is normally answered from a ready index.
- **One walk at a time.** A warm-up and a search that arrive together share the
  same walk instead of paying for it twice.
- **A stale index still answers.** Names older than five minutes are refreshed
  *in the background*; the search that notices the age answers from what it has
  and the next keystroke sees the new names.
- **Only the first search of a session can wait**, and then only for the walk
  already in flight, capped at 25 seconds.
- Breadth-first, capped at 100,000 entries, 200 results, hidden names skipped,
  `Android/data` and `Android/obb` skipped.

Measured after the fix: a cold-start search round trip over the companion
channel is **~0.4 s**.

### Why the answer is a `file-list`

A search answer is an ordinary `file-list` message whose `path` field carries
`search:<query>` instead of a folder path:

```
{"path": "search:jpg", "entries": [...]}
```

A folder path never contains a leading `search:`, so the two cannot be
confused. This means the desktop resolves a search with the same map it uses
for folders - no second parser, no new payload shape - and a folder listing and
a search in flight cannot resolve each other.

## The desktop side

- `services/companion.js` - `requestFileList(path)` and
  `requestFileSearch(query)`, both routed through one `awaitListing()` helper
  that keys the pending map and applies the right deadline (10 s for a folder,
  40 s for a search, because the phone may still be walking).
- `main.js` - IPC `companion:listFiles`, `companion:searchFiles`,
  `companion:downloadFile`.
- `preload.js` - `window.api.listPhoneFiles`, `searchPhoneFiles`,
  `downloadPhoneFile`.
- `src/renderer.js` - the phone card. Typing in the search box debounces 220 ms,
  then searches; a folder in the results opens that folder; a file gets a
  download button. Every answer carries a sequence number so a slow reply to an
  older keystroke is dropped instead of overwriting newer rows.
- Pulled files land in the desktop's normal downloads folder through the
  existing file-start/chunk/done transfer - the phone streams them with a
  `FileInputStream`, no content provider involved.

## Verifying it

Desktop checks (no phone needed):

```
node scripts/protocol-check.js          # the search request and its keyed answer
node scripts/check-companion-files.js   # search resolves, folders first, no cross-talk
```

Android unit tests:

```
cd companion-android && ./gradlew :app:testDebugUnitTest
```

Two of those tests are deliberate guards rather than coverage:

- `a stale index answers at once, and refreshes afterwards` walks a fake slow
  storage; it fails if a search ever waits for a refresh.
- `the browser has no way to change anything on the phone` fails the build if
  read-only code grows a write call.

Live check, once the phone is connected:

1. On the phone, Settings, "Files the PC can browse", **Allow...**, turn on the
   switch.
2. On the PC, open the phone card. The root of `/sdcard` lists, `Download`
   opens.
3. Type in the search box. Results come from every folder, and each row shows
   where it lives.
4. Click a folder result - it opens that folder. Click a file's download
   button - it lands in the PC's Downloads folder, byte-identical.
