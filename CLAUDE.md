# SAXBLE iOS — context for Claude

Native iPhone/iPad app for **commissioning SAX-D gas alarms over BLE**. It is the
iOS sibling of the **SAXBLE Cardputer firmware** (separate repo,
`sjbuchanan007/saxble`), hardware-verified against SAX-D firmware **V002_RC302**.
Built with **SwiftUI + CoreBluetooth**. Branded for **SHJ Medical & Lab Gas
Specialists** (blue `#00A0DC` + gray).

## Repo scope & workflow (read first)
- **This repo is the iOS app only.** The Android app lives in a separate private
  repo, **`sjbuchanan007/saxd-android`** (moved out of here with its history).
- **Never leave finished work on a side branch.** When a piece of work is done,
  open a **pull request against `main`** and tell the user, so they can review
  and merge on GitHub. Base all work on `main`.
- **Keep Android in the loop.** For any *significant* change — a new feature, a
  change to how the app talks to the SAX-D over Bluetooth, a change to screens /
  user flow, or a bug fix in shared logic — add a dated entry to
  **`ANDROID-PARITY.md`** (what changed, why, what Android needs to match) and
  repeat that summary in a **"For Android"** section of the PR description. Skip
  this for iOS-only changes (Xcode/Apple-specific fixes, SwiftUI-only tweaks).

## The encoder (hard-won facts — do not "simplify" these away)
- BLE peripheral exposing a **Microchip/ISSC "transparent UART"** service (NOT
  Nordic UART): service `49535343-FE7D-4AE5-8FA9-9FAFD205E455`, write+notify
  char `49535343-1E4D-4BD9-BA61-23C647249616`. Subscribe to all notify chars;
  write to the writable one.
- **It does NOT advertise its service UUID** — scan for everything. Verified on
  hardware: the advert carries **only the Location name** (e.g. `mmssjbt1`,
  `Ward 9`) — no service UUID and **no manufacturer data**, so there is no
  constant "Shire" marker and the name is fully renamable. The scan therefore
  filters to "**named device + no manufacturer data + no service UUID**" (the
  bare RN4870 signature — rename-proof), floats matches to the top, and falls
  back to showing **all** devices if none match. A `Show all` toggle overrides it.
- **Line ending must be CR+LF (`\r\n`)**. LF-only is rejected; CR-only gets no
  reply. (Cosmetic double prompts `> >` are normal with CRLF.)
- **Password is rate-sensitive**: a single bulk write drops the last character.
  Send the password — and any `password <new>` — **one byte at a time** (~35 ms
  apart); see `BLEManager.sendSlow` / `send`. Pacing fixes login, but a
  `password <new>` set can *still* drop the last char, so after a change the app
  reads `settings` back and warns on mismatch (`verifyingPassword` /
  `passwordWarning`). (This is real: a unit once truncated `MMSmms659` → `MMSmms65`.)
- Request/response CLI; the encoder never pushes data unsolicited.
- Login banner contains **`Welcome to Shire`** → logged-in / AUTH.
- Destructive commands prompt **`Y or N`** → answer `Y`. A password change prompts
  **`Retype password`** → resend the same value (slowly); `password updated
  successfully` confirms.
- Prompts (`Password:`, `Y or N`, `>`) have **no trailing newline**, so the line
  assembler also flushes when the buffer ends in `: > ? #`.
- Known passwords: `SHJshj2026--` (current SHJ standard, set by presets),
  `MMSmms659` (previous), `studio3` (factory default). Auto-login tries these in
  order; a `SHJshj2026-` variant is included to survive a dropped last char.

## Architecture
- `BLEManager` (ObservableObject) — the whole transport: scan + filter, connect,
  **login with manual-password fallback**, destructive confirm, password
  retype + verify, and the serial paced-write pipeline. Mirrors the firmware's
  `ble_uart.cpp` + `main.cpp`.
- `Commands.swift` / `Presets.swift` — ported 1:1 from the firmware's
  `commands.cpp` / `presets.cpp`. Keep in sync if the firmware changes. Command
  grids are ordered as a commissioning workflow (setup → alarms → text →
  destructive last). Presets end with `gas a list` + `settings`.
- `Report.swift` — parses `gas a list` / `settings` into a structured **PDF
  commissioning report** (SHJ letterhead `shj-logo` + gas table with alarm
  differentials + system settings + appended transcript), named by location +
  date, shared via `ShareLink`. Uses **fixed** colours so it renders correctly in
  dark mode.
- UI (SwiftUI): `SAXBLEApp` → `RootView` gates on **`loggedIn`** (not merely
  connected) → `ScanView` (device list, live status, manual-password prompt) or
  `ConnectedView` (TabView: **Gas / General** command-card grids → command detail
  with channel + param + destructive confirm, **Presets** with location prompt,
  **Console**). `Haptics.swift` = taps/success/warning feedback.

## Build / constraints
- SwiftUI + CoreBluetooth, deployment target **iOS 17**.
- Needs a Mac + Xcode; CoreBluetooth needs a **real device** (no Bluetooth in the
  simulator). Set a signing Team. `project.yml` is an XcodeGen definition; see
  `README.md` for both XcodeGen and by-hand setup.
- The dev container can't build/run iOS — flash to an iPhone and iterate from the
  in-app **Console** (and the Xcode console).

## Done
- Scan with the rename-proof encoder filter + `Show all` fallback.
- Login flow: auto-login candidates, **manual-password prompt** on failure,
  UI gated on actual login, password set + read-back verification.
- Full command set (Gas/General grids, workflow order) + command detail.
- Presets: 2-gas, 3-gas, and two **6-gas** layouts; mid-run location prompt.
- **PDF commissioning report** (SHJ letterhead, gas/alarm table, settings,
  transcript), named by location + date.
- SHJ theming (brand blue/gray, gradient nav, app icon = "SHJ / SAX" wordmark),
  haptics.

## Not done yet / ideas
See `ROADMAP.md` for detailed requirements on the first two.
- Persist a password list + last-used (currently hard-coded candidates).
- Per-device session history / saved reports (currently share-on-demand).
- Editable presets in-app.
