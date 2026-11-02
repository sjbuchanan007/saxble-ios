# Android parity log

The Android app lives in **`sjbuchanan007/saxd-android`**. This file tracks
iOS changes that the Android app needs to mirror, so the two stay in step.

**When to add an entry (and a "For Android" PR section):** a new feature, a
change to how the app talks to the SAX-D over Bluetooth, a change to screens /
user flow, or a bug fix in shared logic. **Skip** iOS-only changes (Xcode /
Apple-specific, SwiftUI-only cosmetics with no behavioural effect).

Newest first. Each entry: date · what changed · why · what Android must do.

---

## 2026-11-02 — Baseline (repo split)
**What:** The Android app was moved out of this repo (the former `android-poc/`)
into its own repo, `saxd-android`, with history. At this point it was a
**feature-matched Kotlin + Jetpack Compose port** of the iOS app, hardware-
verified on Android.

**Parity status at split — Android already matches iOS on:**
- Encoder transport: transparent-UART UUIDs, CR+LF, paced/serialised writes,
  line assembler (flush on `: > ? #`), `Welcome to Shire` login, `Y/N` confirm,
  `Retype password` + read-back verify.
- Scan filter: "named + no manufacturer data + no service UUID" with `Show all`
  fallback.
- Login flow gated on actual login; manual-password prompt on auto-login failure.
- Command catalogue + presets (incl. two 6-gas layouts), commissioning-workflow
  ordering, wrapping channel chip picker.
- Auto commissioning **PDF report on disconnect** (runs `gas a list` + `settings`
  first), SHJ letterhead via `assets/shj_logo.png`, transcript excludes scan ADV
  diagnostics.
- SHJ theming + "SHJ / SAX" app icon (installed via Android Image Asset studio).
- Password standard `SHJshj2026--` and the auto-login candidate list.

**Android-specific notes (not parity, but context):** Android enables
notifications via the CCCD descriptor; writes are serialised by waiting for each
`onCharacteristicWrite` callback; runtime `BLUETOOTH_SCAN`/`CONNECT` permissions;
report shared via `FileProvider` + `ACTION_SEND`.

From here on, new iOS changes that affect shared behaviour get their own entry
below (newest at the top).
