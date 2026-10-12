# Thor Feeds

Thor Feeds is an Android catalog for the AYN Thor. You point it at your own JSON collection feeds, browse one row per game, and download a release into a games folder you choose. It does not ship feeds, credentials, or game files.

## Add a feed

A feed is a `DownloadSourceFeed`:

```json
{
  "name": "My shelf",
  "downloads": [
    {
      "title": "Example Quest Free Download (v1.0.0)",
      "uploadDate": "2026-10-11T19:02:09+00:00",
      "fileSize": "5.9 GB",
      "uris": [
        "https://example.test/mirror-a",
        "https://example.test/mirror-b"
      ]
    }
  ]
}
```

- `title` is the release row. The catalog name is the title with the `Free Download` boilerplate, and any leftover version or build tag, removed.
- `fileSize` is shown as written. It is not treated as an exact byte count.
- `uris` is an ordered list of mirrors. The first link is tried first. If that host fails, the next link is tried.

In the app, open **Feeds** and choose **Add URL**. Paste an `http` or `https` URL. Nothing is prefilled.

You can also choose **Import JSON** and pick a local file with the same shape. Web pages, including challenge pages, are rejected.

Edit or remove a remote URL from the same screen. Removing a feed does not delete files already in the games folder.

## Games folder

Choose a folder with the system picker. Thor Feeds keeps that permission and downloads directly into it.

GameHub Lite does not publish a required library layout for manually imported PC games. Import is “pick the game’s `.exe`”. This app therefore uses one subdirectory per canonical title:

```text
<games folder>/
  Example Quest/
    example-quest.zip
```

The file name comes from the host. Archives are not extracted, and GameHub Lite’s private library is not edited. After a download finishes, import the game’s `.exe` from that folder once the archive is extracted, the same way as a manual copy.

If a top-level file, a non-empty top-level folder, or a file inside a top-level folder already matches the canonical title, the game is marked **On device** and is not downloaded again unless you choose **Download again**. Partial downloads do not count. Matching ignores case, the `Free Download` suffix, and punctuation differences such as `:`.

## Two screens

When a second display is on and **Use the other screen** is enabled (the default):

- The catalog stays on the display where you opened Thor Feeds.
- The selected game, older builds, and download progress open on the other display.
- Closing that screen leaves it closed until you pick a game again.

With one display, the catalog and the detail screen are normal in-app pages.

Thor Feeds was not run on a physical AYN Thor. A headless Android 34 emulator was started here, but QEMU stalled before `adb` connected, so a simulated second display was not exercised on a running device. Choosing the other display is unit-tested. The app itself uses `DisplayManager`, `ActivityOptions.setLaunchDisplayId`, and a `Presentation` fallback.

## Downloads

The active mirror is shown as its host and its position in the list, for example `Mirror 1 of 2 · Gofile`, with bytes read and a cancel button. A notification offers the same cancel action.

- Gofile share pages (`/d/{id}`) are resolved to the file Gofile lists for that link. A direct storage URL is downloaded as a file. If resolution or the transfer fails, the next `uris` entry is used.
- 1fichier and DataNodes share pages follow that host’s own download form once. A wait or a missing file fails the mirror.
- VikingFile uses a direct link when the page already exposes one. If the page asks for an interactive check, that mirror is skipped. Thor Feeds does not try to solve that check.
- Any other URL is treated as a direct file. An HTML response is a failed mirror, not a saved game.
- Resume sends `Range` only after that same mirror has advertised `Accept-Ranges: bytes` or already returned `206`. Otherwise the file starts over.

One download runs at a time. Leaving the app is covered by a foreground service. Force-stopping the app stops the transfer; the next **Download** continues only when the host supports resume.

## Build

```shell
cd thor-feeds
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. `minSdk` is 26. Set `ANDROID_HOME` or `local.properties` (`sdk.dir`) to an Android SDK with platform 35.
