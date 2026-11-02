# sr. tv

The Android TV app for [strangeramblings.com](https://strangeramblings.com). It is a thin shell: one
full-screen WebView on `https://strangeramblings.com/games/tv`. Every screen is a web page on the site,
so the app itself rarely changes. Built for a Sony Bravia (Google TV), works on any Android TV 7.0+ box,
and on phones and tablets as a plain launcher app.

## What the shell adds

- A TV launcher entry (`LEANBACK_LAUNCHER`, 320x180 banner) and a normal launcher entry.
- Keeps the screen on, runs immersive full-screen, landscape.
- D-pad keys go straight to the page.
- Back asks the page first: it calls `window.srTvBack()`; if that returns `true` the page handled it,
  otherwise the WebView goes back, otherwise the app closes.
- Cookies persist (first-party only), so a linked TV stays linked.
- Navigation stays on `strangeramblings.com` and its subdomains; other links are not opened.
- If the site can't be reached, a bundled offline page with a Retry button.
- `window.SRTV.version()` returns the installed versionCode. The user agent ends in `SRTV/<versionName>`.
- Self-update: on launch it reads `https://strangeramblings.com/tv/version.json`
  (`{ "versionCode", "versionName", "url" }`). If a newer build exists it offers to download and install it.
  Any failure there is silent.

## Build and release

Every push to `main` (or a manual run of the **Release** workflow) builds a signed release APK on GitHub
Actions and publishes it as the `sr-tv.apk` asset of a new GitHub release `v1.0.<run number>`, marked latest.
`versionCode` is the workflow run number, `versionName` is `1.0.<run number>`.

The latest APK is always at:

    https://github.com/zerosumpain/SR-TV/releases/latest/download/sr-tv.apk

Signing uses four repository secrets: `KEYSTORE_B64` (base64 of the keystore), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Nothing secret lives in this repo.

Local build (JDK 17 and an Android SDK with platform 35):

    ./gradlew assembleDebug                      # debug build
    KEYSTORE_PATH=... KEYSTORE_PASSWORD=... KEY_ALIAS=srtv KEY_PASSWORD=... ./gradlew assembleRelease

## Install on the TV

1. On the TV: Settings → System → About → click **Android TV OS build** seven times to turn on Developer options.
2. Install **Downloader** (by AFTVnews) from the Play Store.
3. Settings → Apps → Security & restrictions → **Install unknown apps** → allow **Downloader**.
4. Open Downloader and type `strangeramblings.com/tv/get`. Install when asked.
5. sr. tv appears in the Apps row. Later updates are offered by the app itself (allow it to install
   unknown apps when it asks).

### adb fallback

Turn on Network debugging in Developer options, then from a computer on the same network:

    adb connect <tv-ip>:5555 && adb install -r sr-tv.apk

## Signing key

The release keystore and its passwords are backed up outside any repo at `~/.config/sr-tv/` on homeserv.
Do not lose it: an APK signed with a different key cannot install over the existing app, so every TV
would have to uninstall and lose its cookies (its link) first.
