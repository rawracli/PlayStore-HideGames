# PlayStore-HideGames

An **LSPosed module** that hides games from the Google Play Store — they disappear
from both **search results** and the **home feed**, and opening a game's detail
page via a direct link is **blocked**.

- No Play Store APK modification, no re-signing, no replacement — everything is
  done via Xposed hooks at runtime.
- Works on the current Play Store (tested on `53.6.15`, Android 12).
- Filtered as you type: results are re-emitted so hidden entries never flash on
  screen.

## What it does

| Behavior | Details |
| --- | --- |
| Hide from search | Typing a game name (e.g. "free fire") shows no game results. |
| Hide from home feed | Game entries in the Play Store home are filtered out. |
| Block detail links | `https://play.google.com/store/apps/details?id=...` and `market://details?id=...` links for known games are blocked instantly. |
| Late recognition | If a game page opens before it is recognized, the page closes itself once identified. |
| Learns games | The module builds a cache of game package names from the store UI and persists it across restarts. |

Non-game apps (e.g. Spotify) are unaffected — their store pages open normally.

## Requirements

- Magisk (or any su provider) + **LSPosed**
- Android 8.0+ (minSdk 26), Play Store as scope target
- LSPosed scope: `com.android.vending` (Google Play Store)

## Install

1. Download the latest APK from [Releases](../../releases).
2. Install the APK, enable the module in LSPosed, and scope it to
   **Google Play Store** (`com.android.vending`).
3. Force-stop and reopen the Play Store.

## Build from source

```sh
git clone https://github.com/rawracli/PlayStore-HideGames.git
cd PlayStore-HideGames
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### Release builds (signed)

Release builds are signed with a proper keystore, read from either:

- `keystore.properties` in the repo root (local builds, gitignored):

  ```properties
  storeFile=keystore/hidegames.jks
  storePassword=...
  keyAlias=hidegames
  keyPassword=...
  ```

- Environment variables (CI): `KEYSTORE_BASE64` (base64 of the keystore file),
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Without any of these, `assembleRelease` still works and produces an unsigned
release APK.

```sh
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## How it works

The module hooks the Play Store UI layer only:

- **`NodeFilter`** — intercepts rendering of search results and home feed nodes,
  drops nodes belonging to known game packages, and keeps a package-name cache
  in the Play Store's own `SharedPreferences`.
- **`LinkBlock`** — watches Activity creation in the Play Store, blocks detail
  pages for known games the moment they are opened, and closes pages that are
  recognized as games slightly later (after the document is parsed).

Everything runs inside `try/catch` hooks — a failure in this module never
crashes the Play Store.

## Caveats

- Hiding is based on a learned package-name cache. A game page may be shown
  once before its package name is learned, but subsequent appearances are
  filtered.
- "Load more" pagination in filtered lists is intentionally cut off once a page
  contains hidden entries (it prevents an endless loading spinner).

## License

[MIT](LICENSE)
