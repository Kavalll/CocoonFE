# Cocoon RomM Store

Cocoon's public GitHub repo only ships **platform definitions**. The launcher APK is closed source, and official RomM support is still on the Cocoon roadmap ([discussion #210](https://github.com/inssekt/CocoonFE/discussions/210), [Post 2.0 roadmap](https://cocoon-shell.com/news/post-2-0/)). Nothing in the platform JSON files can download ROMs.

This companion is the integration that *can* ship in this repo: a handheld-friendly RomM store you pin to the Cocoon dock. It talks to the RomM server you already run, maps platforms onto Cocoon unique IDs, and writes games into the same folders Cocoon already scans.

It is **not a second launcher**. Keep Cocoon as home. Use this only to pull games down.

## What you get

- Sign in with username/password, a `rmm_` client token, or an 8-digit pairing code from RomM → Administration → Client API Tokens
- Browse platforms and games from your server
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

`npm run build` inlines the UI into a single `index.html` (no ES modules) and copies it into `android/app/src/main/assets/www/`. Do not copy `dist/` by hand; leftover `type="module"` scripts are what made Thor show a blank screen.

```bash
cd romm-store
npm install
npm run build
```

Package id: `app.cocoon.rommstore`. Version **1.0.4**. Launcher name: **RomM Store 1.0.4**.

This lives on the PR branch `cursor/romm-store-companion-7dfa`, not `main`. If you `git pull` on `main` you will keep the old white-screen APK.

```bat
cd C:\Users\kaval\CocoonFE
git fetch origin
git checkout cursor/romm-store-companion-7dfa
git pull origin cursor/romm-store-companion-7dfa
git log -1 --oneline
```

That log line must mention **1.0.4**. In Android Studio, `romm-store/android/THIS_IS_VERSION_104.txt` must exist. `MainActivity.kt` must contain `R.raw.store`.

Easiest install: open the GitHub PR → **Checks / Actions** → **RomM Store APK** → download `CocoonRommStore-debug` → copy `app-debug.apk` to the Thor and install it. Uninstall the old app first. The launcher icon will say **RomM Store 1.0.4**.

### Run on the Thor from Android Studio (wireless debugging)

Logcat that mentions `index-dSIPXYpo.js` and never `RommStore` is still the **old APK**. Studio did not build this folder.

Open **this folder**, not the CocoonFE repo root:

`C:\Users\kaval\CocoonFE\romm-store\android`

1. Confirm `THIS_IS_VERSION_104.txt` is in that folder.
2. **File → Sync Project with Gradle Files**, then **Build → Clean Project**.
3. Uninstall **Cocoon RomM Store** on the Thor.
4. **Run**. You should get a toast **RomM Store 1.0.4**, then **Connect RomM**.
5. Logcat filter `RommStore` must include `boot 1.0.4 raw/store.html`. It must **not** mention `index-dSIPXYpo.js`.

If the launcher still says **Cocoon RomM Store** without 1.0.4, the new APK did not install.

You can still export an APK with **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The file is `romm-store\android\app\build\outputs\apk\debug\app-debug.apk`.

If Run or Build does nothing, open **View → Tool Windows → Build** and **Gradle**. Install **SDK Platform 35** and **Android SDK Build-Tools** from **Settings → Languages & Frameworks → Android SDK**.

## Token scopes

A read-only client token with `roms.read` is enough to list and download. Create it in RomM under Administration → Client API Tokens. Do not paste that token into a public issue.

## Limits

- This cannot add a RomM tab inside Cocoon itself. That needs the closed launcher source (or the official Amber work the Cocoon team already said they are doing).
- Save sync, firmware/BIOS copy, and RetroAchievements-from-RomM are out of scope here. Downloads into Cocoon folders are the goal.
- If your reverse proxy sets a tight CORS allowlist, add the origin you serve this app from, or use the Android wrapper (native HTTP, no CORS).
