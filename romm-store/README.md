# Cocoon RomM Store

Cocoon's public GitHub repo only ships **platform definitions**. The launcher APK is closed source, and official RomM support is still on the Cocoon roadmap ([discussion #210](https://github.com/inssekt/CocoonFE/discussions/210), [Post 2.0 roadmap](https://cocoon-shell.com/news/post-2-0/)). Nothing in the platform JSON files can download ROMs.

This companion is the integration that *can* ship in this repo: a handheld-friendly RomM store you pin to the Cocoon dock. It talks to the RomM server you already run, maps platforms onto Cocoon unique IDs, and writes games into the same folders Cocoon already scans.

It is **not a second launcher**. Keep Cocoon as home. Use this only to pull games down.

## What you get

- Sign in once; the Thor keeps that login until you tap Log out
- Browse platforms (newest generation first) and games from your server
- Filter consoles separately from searching game titles
- Download a game into `ROM_ROOT/<cocoon-unique-id>/<filename>`
- Folder layouts:
  - **Cocoon unique IDs** (default): `gb`, `snes`, `n64`, `gc`, `n3ds`, `mastersystem`, …
  - **RomM slugs**: `gb`, `snes`, `n64`, `ngc`, `3ds`, `sms`, …
  - **Match existing folders**: uses `gamecube` if that directory is already there
- After a download, rescan the platform in Cocoon if the new file does not show up immediately

`platform-map.json` is the Cocoon ↔ RomM table (GameCube `gc` ↔ RomM `ngc`, 3DS `n3ds` ↔ `3ds`, ZX Spectrum `zxspectrum` ↔ `zxs`, and so on).

## Use it on an AYN Thor

1. Open this web app on the device (Chrome, or the Android wrapper below).
2. Enter your RomM URL. If you reach it over Tailscale, use that Tailscale URL, same as Argosy.
3. Sign in. For a handheld, a pairing code is easier than typing a long token.
4. In Settings, choose the **same ROM root** Cocoon scans.
5. Download a game, then go back to Cocoon and rescan that platform.
6. Pin this app to the Cocoon dock: long-press / Start → add the app shortcut. You never have to switch to Argosy as a launcher.

If the browser cannot write into your ROM folder, files land in Downloads. Move them once, or use the Android wrapper, which uses Storage Access Framework to write into the folder you pick.

## Run locally

```bash
cd romm-store
npm install
npm test
npm run dev
```

`npm run dev` serves the UI at `http://localhost:5173`. Against the public kiosk at `https://demo.romm.app` you can browse without logging in and download the demo homebrew titles.

## Android wrapper

`android/` is a WebView shell with a `CocoonRomm` bridge:

- pick a ROM root folder
- stream downloads straight into that tree
- list existing platform folders so alias matching works

`npm run build` inlines the UI into a single `index.html` (no ES modules) and copies it into `android/app/src/main/assets/www/` and `res/raw/store.html`. Do not copy `dist/` by hand; leftover `type="module"` scripts are what made Thor show a blank screen.

```bash
cd romm-store
npm install
npm run build
```

Package id: `app.cocoon.rommstore`. Version **1.0.7**. Launcher name: **RomM Store 1.0.7**.

This lives on the PR branch `cursor/romm-store-companion-7dfa`, not `main`. If you `git pull` on `main` you will keep an old APK.

## Install 1.0.7 on an AYN Thor

Uninstall whatever is already named Cocoon RomM Store / RomM Store first. Android will keep serving the old WebView bundle if you just hit Run over the top of 1.0.0–1.0.5.

### Option A — download the CI APK (fastest)

1. On a PC, open https://github.com/Kavalll/CocoonFE/pull/1
2. Wait until the **RomM Store APK** check is green on the latest commit.
3. Click **Checks / Actions** → **RomM Store APK** → artifact **CocoonRommStore-debug** → download the zip.
4. Unzip. Copy `app-debug.apk` to the Thor (USB, Syncthing, or a shared folder).
5. On the Thor: Settings → Apps → **RomM Store** / **Cocoon RomM Store** → Uninstall.
6. Open the APK (Files app) and install. Allow unknown sources if Android asks.
7. The launcher icon must say **RomM Store 1.0.7**. If it does not, you installed an older file.

### Option B — git pull and Run from Android Studio

On **Windows cmd** (not Git Bash, unless you know that environment):

```bat
cd C:\Users\kaval\CocoonFE
git fetch origin
git checkout cursor/romm-store-companion-7dfa
git restore romm-store/android/app/src/main/assets/www
git restore romm-store/package-lock.json
git clean -fd romm-store/android/gradle romm-store/android/gradlew romm-store/android/gradlew.bat
del romm-store\android\gradlew 2>nul
del romm-store\android\gradlew.bat 2>nul
git pull origin cursor/romm-store-companion-7dfa
git log -1 --oneline
```

`git log -1` should mention 1.0.7. Confirm this file exists:

`C:\Users\kaval\CocoonFE\romm-store\android\THIS_IS_VERSION_107.txt`

If `git pull` still refuses, Android Studio has leftover hashed JS. Discard it:

```bat
cd C:\Users\kaval\CocoonFE
git restore romm-store/android/app/src/main/assets/www
git clean -fd romm-store/android/app/src/main/assets/www
git pull origin cursor/romm-store-companion-7dfa
```

Then in Android Studio:

1. **File → Open** the folder `C:\Users\kaval\CocoonFE\romm-store\android` — not the CocoonFE repo root.
2. If it says **Incompatible Gradle JVM** (8.9 vs JVM 25): **Apply compatible Gradle JDK configuration and sync**, or **Settings → Build Tools → Gradle → Gradle JDK → 17** (Temurin 17 is fine). Do not stay on JDK 25.
3. **File → Sync Project with Gradle Files**, then **Build → Clean Project**.
4. Enable **Wireless debugging** on the Thor, pair it in Studio.
5. Uninstall **RomM Store** on the Thor.
6. Click **Run** (green triangle), not the hammer. The hammer does not install.
7. You should get a toast **RomM Store 1.0.7**, then **Connect RomM**.
8. Logcat filter `RommStore` must include `boot 1.0.7 raw/store.html`. It must **not** mention `index-dSIPXYpo.js`.

Export an APK instead with **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The file is `romm-store\android\app\build\outputs\apk\debug\app-debug.apk`. Copy that to the Thor.

If the launcher still says **Cocoon RomM Store** without **1.0.7**, the new APK did not install.

If Run or Build does nothing, open **View → Tool Windows → Build** and **Gradle**. Install **SDK Platform 35** and **Android SDK Build-Tools** from **Settings → Languages & Frameworks → Android SDK**.

After it launches: enter your RomM URL (Tailscale URL is fine), sign in or browse, **Settings → Choose ROM root folder**, pick the same ROM root Cocoon scans, download a game, then rescan that platform in Cocoon. Pin **RomM Store 1.0.7** to the Cocoon dock.

## Token scopes

A read-only client token with `roms.read` is enough to list and download. Create it in RomM under Administration → Client API Tokens. Do not paste that token into a public issue.

## Limits

- This cannot add a RomM tab inside Cocoon itself. That needs the closed launcher source (or the official Amber work the Cocoon team already said they are doing).
- Save sync, firmware/BIOS copy, and RetroAchievements-from-RomM are out of scope here. Downloads into Cocoon folders are the goal.
- If your reverse proxy sets a tight CORS allowlist, add the origin you serve this app from, or use the Android wrapper (native HTTP, no CORS).
