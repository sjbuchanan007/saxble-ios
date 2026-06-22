# SAXBLE for iOS

A native iPhone/iPad app for commissioning **SAX-D local gas alarms** over
Bluetooth — the same job as the Cardputer firmware, but on the phone you already
carry. Instead of hand-typing into a generic *BLE Terminal* app, you connect,
auto–log in, and drive the encoder from baked-in menus and presets, with the
whole session captured for a commissioning report.

Built with **SwiftUI + CoreBluetooth**. CoreBluetooth as a BLE central is rock
solid, so none of the radio headaches of the abandoned Tab5 port apply here.

> **Status:** first build, not yet run on hardware. Mirrors the hardware-verified
> Cardputer logic (encoder protocol, CR+LF login, paced password, full command
> set, presets). Build it, run on your iPhone next to the encoder, and report
> back — same iterate-and-fix loop as the firmware.

## What it does
- **Scan & connect** — lists nearby BLE devices; the encoder (advertised under
  its Location name) is floated to the top. Tap to connect.
- **Auto-login** — on the encoder's `Password:` prompt it sends the saved
  passwords (CR+LF, one character at a time) until one works; an **AUTH**
  indicator lights on `Welcome to Shire`.
- **Command menus** — the full SAX-D gas + general command set; pick a command,
  choose the channel (`1`–`6`/`All`), enter only the valid parameter (number /
  text / pick-list). Destructive commands confirm first and auto-answer `Y`.
- **Presets** — one tap runs a whole sequence (set gas types, disable unused
  gases, enable pressure display, set password, prompt for location, list).
- **Console** — live transcript of everything to/from the encoder, a raw-command
  field, and a **Share** button to export the session (AirDrop / email / Files).

## Setup

You need a **Mac with Xcode** and (to run on a real iPhone) an Apple ID for
signing — free works (7-day re-install) or a paid Apple Developer account.

### Option A — XcodeGen (recommended, reproducible)
```bash
brew install xcodegen      # once
xcodegen generate          # in this folder → creates SAXBLE.xcodeproj
open SAXBLE.xcodeproj
```
Then in Xcode: select the **SAXBLE** target → **Signing & Capabilities** → pick
your **Team**, plug in your iPhone, and **Run**.

### Option B — create the project by hand in Xcode
1. **File ▸ New ▸ Project… ▸ iOS ▸ App**, name it `SAXBLE`, Interface **SwiftUI**,
   Language **Swift**.
2. Delete the template's `ContentView.swift` / `…App.swift`.
3. Drag **all the `.swift` files from `SAXBLE/`** into the project (check
   "Copy items if needed").
4. Add the Bluetooth permission: target ▸ **Info** ▸ add
   **Privacy – Bluetooth Always Usage Description** with a short string (or use
   the included `Info.plist`).
5. Pick your signing Team and Run.

## Project layout
| File | Responsibility |
|------|----------------|
| `SAXBLE/SAXBLEApp.swift` | app entry + root (scan vs connected) |
| `SAXBLE/Encoder.swift` | encoder BLE facts (UUIDs, CR+LF, passwords, marker) |
| `SAXBLE/BLEManager.swift` | CoreBluetooth central: scan/connect/login/send |
| `SAXBLE/Commands.swift` | full SAX-D command catalogue (ported from firmware) |
| `SAXBLE/Presets.swift` | preset sequences |
| `SAXBLE/ScanView.swift` / `ConnectedView.swift` / `PresetsView.swift` / `ConsoleView.swift` | UI |

See `CLAUDE.md` for the encoder protocol details and the decisions behind this
code (useful context if you continue the work in a new Claude Code session).
