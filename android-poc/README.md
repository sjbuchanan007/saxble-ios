# SAXBLE Android — Proof of Concept

Starter Kotlin for the Android port's **hardest slice**: scan → connect →
enable notifications → paced CR+LF login → detect `Welcome to Shire`. If this
logs into the encoder from Android, the rest (command grids, presets, PDF
report) is just re-expressing the proven iOS logic.

Branding is kept neutral (**SHJ**, no Shire logo) per the plan.

> This code is written against the Android BLE APIs but **has not been compiled
> or run on hardware** — expect to iterate on-device (BLE varies by phone), the
> same loop as the iOS app. The paced-write gap especially may need tuning.

## Create the project
1. Android Studio → **New Project → Empty Activity** (the Compose one).
2. Name: **SAXBLE**, Package name: **`com.shj.saxble`**, Language **Kotlin**,
   **Minimum SDK = API 26 (Android 8.0)**.
   - If you pick a different package name, change the `package com.shj.saxble`
     line at the top of all three `.kt` files to match.
3. Let it finish the first Gradle sync (needs internet the first time).

## Drop in these files
Replace / add in `app/src/main/java/com/shj/saxble/`:
- `MainActivity.kt`  ← replace the generated one
- `BleManager.kt`    ← add
- `Encoder.kt`       ← add

Then open `app/src/main/AndroidManifest.xml` and paste the contents of
`AndroidManifest-permissions.xml` inside `<manifest>` **above** `<application>`.

No extra Gradle dependencies are needed — the Empty Compose Activity template
already includes Compose, `activity-compose`, Material 3, and coroutines.

## Run it
- **On the handset** (not the emulator — the emulator has no Bluetooth): enable
  Developer Options + USB debugging, plug in, pick it in the device dropdown,
  press **Run** (▶).
- Grant the Bluetooth permission prompt, tap **Scan**, then tap the encoder in
  the list (it advertises under its Location name).
- Watch the **Console**: you should see `Password:` → the paced password →
  `Welcome to Shire…`, and **AUTH** lights up.

## What to watch / likely tweaks
- **Permissions**: on Android 12+ the app asks for Bluetooth at launch. If scan
  returns nothing, confirm the grant and that Bluetooth + Location (device-level)
  are on.
- **Paced write**: `Encoder.SLOW_BYTE_GAP_MS` (35 ms) mirrors iOS. Android waits
  for each write's completion callback *and then* delays, so you may be able to
  lower it — but don't rush it, the last-character drop is why it exists.
- **Notifications**: Android needs the CCCD descriptor write (handled in
  `enableNotify`). If replies never arrive, that's the first place to look.
- **`WRITE_TYPE`**: the code picks NO_RESPONSE if the characteristic supports it,
  else default — same choice as iOS.

## How this maps to the iOS app
| iOS | Here |
|---|---|
| `Encoder.swift` | `Encoder.kt` |
| `BLEManager.swift` (scan/connect/login/serial write) | `BleManager.kt` |
| `ScanView` + `ConsoleView` (minimal) | `MainActivity.kt` (one screen) |

Not yet ported: command catalogue, presets, PDF report, password store,
history. See `../ROADMAP.md` → "Android port".
