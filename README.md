# ROCKSTAR PORT (launcher)

Imports your own game ZIP (needs `data/manifest.json`, `b/<build>/game.wasm`, `b/<build>/shaders/index.json`,
at any depth inside the ZIP), extracts it with CRC32 verification to `/sdcard/GrandV-assets`, checks device
compatibility and starts the installed runtime package (default `dev.muguet`).

## Build the APK (GitHub)
1. Create a new GitHub repo and upload everything in this folder (keep `.github/workflows/build.yml`).
2. Open the **Actions** tab -> **Build APK** -> *Run workflow* (it also runs on every push).
3. When it finishes, open the run and download the **RockstarPort-debug-apk** artifact.
