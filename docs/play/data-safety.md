# Data safety answers (draft, state after T-007)

Marked CHECK = not confirmed in code. Answer the form only after checking each one.

## Overview

- No ads SDK, no analytics or crash-reporting library found in the Gradle files and sources searched. CHECK: scan the full dependency tree (`./gradlew :app:dependencies`) and native libraries.
- Slicing and model editing run on the device. Models, G-code and profiles stay on the device.
- Data collected by the developer: none. CHECK: no developer-run server exists.

## Data the app sends off the device, only on user action

| Purpose | Destination | Notes |
|---|---|---|
| Printing to the user's printer | printer or host on the local network | user-entered address and credentials |
| Bambu cloud login and printing | Bambu Lab servers (bambulab.com) | CHECK: exact data sent; account email and token pass through |
| Profile and community lookups | OrcaSlicer services (api, auth, cloud) | CHECK: which are called in this build, and whether any is an account login |
| Update and profile downloads | GitHub (api.github.com, raw.githubusercontent.com) | CHECK: whether an update check runs without user action |
| Built-in browser to model sites | MakerWorld, Printables, Thingiverse | third-party sites, their own policies; `AdBlock.kt` filters requests |
| Optional AI/MCP helper | local token, see below | CHECK: whether any data leaves the device |

## Form answers

- Data collected: CHECK. Likely "No" for developer collection, since data goes only to the user's chosen printer or third-party services the user signs into. Confirm Play's rule on user-initiated transfers.
- Data shared with third parties: CHECK (Bambu cloud login).
- Personal info (name, email): CHECK, Bambu account email handled in the app.
- Location: not requested. CHECK: no location permission in the manifest (none found); local network access is declared (`ACCESS_LOCAL_NETWORK`).
- Photos, files: user-picked models through the system picker. CHECK: no broad storage permission (none in manifest).
- Camera: the printer camera stream is viewed, not recorded. CHECK.
- Encryption in transit: CHECK. Bambu cloud uses TLS; local hosts may use plain HTTP.
- Deletion request: CHECK. All data is local, so uninstall removes it; cloud account data is held by the vendor.

## Credentials and backup (T-007)

- Connection keys and the MCP token are stored in secure settings, not in `app_settings` (verified in T-007: `conn:=0 ai_token=0` in plain prefs, `conn:=1 ai_token=1` in secure prefs).
- Manifest has `allowBackup="true"` with `data_extraction_rules` and `backup_rules`. CHECK: what the rules include.
- Known gap (T-007 note): `AppBackup.kt` lists only `app_settings` in PREFS, so an old export can restore plaintext keys until migration moves them again. CHECK: fixed or not.
