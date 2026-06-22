# SAXBLE iOS — context for Claude

This app is the iOS sibling of the **SAXBLE Cardputer firmware** (separate repo,
`sjbuchanan007/saxble`). It commissions **SAX-D gas alarms** over BLE. The
firmware is hardware-verified against SAX-D firmware **V002_RC302**; this app
ports that proven behaviour to SwiftUI + CoreBluetooth.

## The encoder (hard-won facts — do not "simplify" these away)
- BLE peripheral exposing a **Microchip/ISSC "transparent UART"** service (NOT
  Nordic UART): service `49535343-FE7D-4AE5-8FA9-9FAFD205E455`, write+notify
  char `49535343-1E4D-4BD9-BA61-23C647249616`. Subscribe to all notify chars;
  write to the writable one.
- **It does NOT advertise its service UUID** — scan for everything and match by
  the advertised name (the encoder's *Location* string, e.g. `mmssjbt1`).
- **Line ending must be CR+LF (`\r\n`)**. LF-only is rejected; CR-only gets no
  reply. (Cosmetic double prompts `> >` are normal with CRLF.)
- **Password is rate-sensitive**: a single bulk write drops the last character.
  Send the password — and any `password <new>` command — **one byte at a time**
  (~35 ms apart). See `BLEManager.sendSlow` / `send`.
- Request/response CLI; the encoder does not push data unsolicited.
- Login success banner contains **`Welcome to Shire`** → set logged-in / AUTH.
- Destructive commands prompt **`Y or N`** → answer `Y`. Changing the password
  prompts **`Retype password`** → resend the same value (slowly). `password
  updated successfully` confirms.
- Prompts (`Password:`, `Y or N`, `>`) have **no trailing newline**, so the line
  assembler also flushes when the buffer ends in `: > ? #`.
- Known passwords: `MMSmms659` (current), `studio3` (documented default).

## Architecture
- `BLEManager` (ObservableObject) = the whole transport + login/confirm/retype
  logic, mirroring the firmware's `ble_uart.cpp` + `main.cpp`.
- `Commands.swift` / `Presets.swift` are ported 1:1 from the firmware's
  `commands.cpp` / `presets.cpp`. Keep them in sync if the firmware changes.
- `Report.swift` parses the encoder's `gas a list` / `settings` replies into a
  structured **PDF commissioning report** (letterhead logo + gas table with
  alarm differentials + system settings, then the full transcript appended).
- UI is plain SwiftUI: `ScanView` → `ConnectedView`, a TabView of **Gas /
  General** (2-column command-card grids) / **Presets** / **Console**.

## Build / constraints
- SwiftUI + CoreBluetooth, deployment target **iOS 17** (uses `ShareLink`,
  `ContentUnavailableView`, two-parameter `onChange`).
- Needs a Mac + Xcode; CoreBluetooth needs a **real device** (the simulator has
  no Bluetooth). Set a signing Team. See `README.md` for setup.
- The dev container can't build/run iOS — flash to an iPhone and iterate from
  the serial-equivalent (the in-app Console + Xcode console).

## Done
- **PDF commissioning report** export (`Report.swift`): structured summary
  (logo + gas/alarm table + settings) plus appended transcript, named by
  location + date. Add a `shire-logo` image to brand the header.

## Not done yet / ideas
- Persist a password list + last-used (currently hard-coded candidates).
- Per-device session history / saved reports (currently share-on-demand).
- Editable presets in-app.
