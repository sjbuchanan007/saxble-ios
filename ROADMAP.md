# SAXBLE iOS — Roadmap

Detailed design notes for planned features. Status keys: **Planned**,
**In progress**, **Done**. See `CLAUDE.md` for the architecture these build on.

---

## 1. Persist a password list + last-used  — *Planned*

### Goal
Replace the hard-coded `Encoder.passwordCandidates = ["MMSmms659", "studio3"]`
with a user-managed, persisted list. Try the **last password that worked**
first, and let the engineer add/edit/remove passwords and enter a one-off
password when none match.

### Why
- Sites have different passwords; hard-coding doesn't scale.
- The encoder's last-character-drop quirk means passwords occasionally end up
  truncated (e.g. `MMSmms65`) — the engineer needs to add the real one on the fly.

### Data model
```swift
struct StoredPassword: Codable, Identifiable, Hashable {
    let id: UUID
    var value: String
    var label: String?      // optional note, e.g. "site default"
    var lastUsedAt: Date?
}
```
- Order for auto-login: most-recent `lastUsedAt` first, then the rest in list order.
- Seed on first launch from the current hard-coded candidates (behaviour unchanged
  for existing users).

### Storage
- **Recommended: Keychain** (these are access credentials). Store the list as one
  JSON blob under a single Keychain item; a thin `KeychainStore` wrapper
  (`get/set Data`) keeps it simple.
- Alternative (MVP): `UserDefaults` — quicker, but credentials in plist is poor
  practice. Decision needed (see Open decisions).

### New type
`PasswordStore: ObservableObject`
- `@Published var passwords: [StoredPassword]`
- `func candidatesInTryOrder() -> [String]`
- `func recordSuccess(_ value: String)` → set `lastUsedAt = now`, persist.
- `add / update / remove / move`, all persisting.

### BLEManager changes (`BLEManager.swift`)
- Inject the store (or pass candidates in). Replace
  `loginCandidates = Encoder.passwordCandidates` (line ~306) with
  `loginCandidates = store.candidatesInTryOrder()`.
- Track the password being attempted: add `private var lastAttempted: String?`
  set in the auto-login send (line ~249).
- On success (`loginMarker`, line ~204): `store.recordSuccess(lastAttempted)`.
- When candidates are exhausted (line ~248 guard fails): set a new
  `@Published var needsManualPassword = true` instead of silently stopping.

### Manual-entry flow
- UI observes `needsManualPassword` → prompt (secure field) → `ble.sendSlow(pw)`
  (reuse paced send) and set `lastAttempted = pw`.
- On success, offer **"Save this password"** → `store.add(...)`.

### UI
- `PasswordsView` — list with add/edit/delete/reorder; values masked with a
  reveal toggle; a star/label on the last-used entry.
- Entry point: a **key icon in `ScanView`'s toolbar** (login happens pre-connect,
  so it belongs on the landing screen — not in the connected TabView).

### Security / UX notes
- Mask passwords in the manager (dots + reveal).
- Passwords still appear in the console transcript and the `settings` dump in the
  PDF. Tie-in with redaction decision under feature 2.
- Keep using `sendSlow` for all password writes (rate-sensitive encoder).

### Edge cases
Empty list, duplicates, leading/trailing spaces, very long values, the list
changing mid-session (re-read on each connect).

### Phasing
1. `KeychainStore` + `PasswordStore` + seed + wire into auto-login & record-success.
2. `PasswordsView` management UI + ScanView entry point.
3. Manual-entry-on-exhaustion + save-on-success.

---

## 2. Per-device session history / saved reports  — *Planned*

### Goal
Persist generated commissioning reports on the device, grouped by encoder, so an
engineer can revisit/share past sessions instead of relying on share-on-demand.

### What to persist
Per saved session:
- The generated **PDF** (the deliverable).
- **Metadata** for browsing (below).
- Optionally the **raw transcript text**, so a report can be re-rendered if the
  PDF format changes later. (Recommended: store both; transcript is tiny.)

```swift
struct SavedReport: Codable, Identifiable {
    let id: UUID
    let deviceKey: String     // grouping key (see below)
    let deviceName: String    // display name
    let date: Date
    let pdfFile: String       // filename under Reports/
    let transcriptFile: String?
    let summary: String?      // e.g. "6 gases · all enabled"
}
```

### Device grouping key
Priority: **encoder Location** (parsed from `settings`, e.g. `mmssjbt1`) →
fall back to advertised name (`peerName`) → fall back to peripheral `identifier`
(UUID). Renaming a location creates a new group; acceptable.

### Storage
- App **Documents** directory: `Reports/<deviceKey>/<id>.pdf` (+ `.txt`).
- A single `Reports/index.json` (`[SavedReport]`) for fast listing.
- Consider `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` later so
  reports show in the Files app (weigh against password exposure — see below).

### New type
`ReportStore: ObservableObject`
- `@Published var reports: [SavedReport]`
- `func save(pdf: URL, transcript: String, deviceKey:, deviceName:, summary:)`
- `grouped() -> [(device: String, items: [SavedReport])]`
- `func url(for:) -> URL`, `func delete(_:)`.

### Save trigger
- MVP: explicit **"Save to history"** in the Console export menu (next to
  Export PDF / Share text).
- Later: auto-save on logout/disconnect *if* a `gas a list`/`settings` was
  captured this session.

### Device key at save time
Reuse `Report.parse(rxLines:)` to read the Location for the key/summary; expose
`peerName` from BLEManager as the fallback.

### UI
- `HistoryView` — sections per device, rows per dated session; tap to preview
  (QuickLook `QLPreviewController`), with share + delete (swipe).
- Entry point: a **clock icon in `ScanView`'s toolbar** (browsing past work
  belongs on the landing screen alongside Passwords).

### Security note (cross-cutting)
Saved/exported PDFs currently contain the login password (the `settings` dump
echoes `Password: "…"`). Decide whether to **redact the password line** in the
PDF (and optionally the `>> password …` transcript lines). Recommended: redact
in the saved/shared artifact, keep it live in the in-app console only.

### Edge cases
Storage growth (allow delete; maybe a cap/cleanup), duplicate timestamps,
missing Location, deleting files + index together, corrupt index recovery.

### Phasing
1. `ReportStore` + Documents/index.json + "Save to history" action.
2. `HistoryView` (grouped list + QuickLook preview + share/delete).
3. Auto-save on logout; optional Files-app exposure; optional password redaction.

---

## Shared concerns
- **UI entry points:** the connected TabView (Gas/General/Presets/Console) is
  full; Passwords + History live on the **ScanView toolbar** (both are
  pre-/post-connection concerns), e.g. a key icon and a clock icon.
- **Persistence layer:** introduces the app's first on-device storage. Keep
  stores small, `Codable`, and independently testable.
- **No network:** everything stays on-device (no account/sync) unless that
  becomes a requirement.

## Decisions (locked)
1. Password storage: **Keychain** (JSON blob under one item). ✅
2. **Redact the password** in saved/shared PDFs (settings dump + `>> password`
   lines); keep it visible in the live in-app console only. ✅
3. History grouping key: **Location → advertised name → peripheral UUID**. ✅ (proposed)
4. Save trigger: **explicit "Save to history" first**, auto-save-on-logout later. ✅ (proposed)
5. Build order / timing: **not yet — revisit after the manufacturer demo.** ✅

---

## Also on the list
- **Editable presets in-app** (currently `Presets.swift` is compile-time).

---

## Android port — *Planned / exploratory*

### The core idea
This is a **rewrite in Kotlin**, not a code port — SwiftUI/CoreBluetooth are
Apple-only. But the *behaviour* is already proven and documented in `CLAUDE.md`
(UUIDs, CR+LF, paced password, login banner, command set, presets, report
parsing). Re-expressing known logic in a new language is the manageable part;
the encoder reverse-engineering (the hard part) is done. Recommendation: a
**native Android app (Kotlin + Jetpack Compose)**, kept as a sibling to the iOS
app rather than replacing it with a cross-platform framework — the encoder's
timing-sensitive quirks want native BLE control.

### iOS → Android mapping
| iOS (this app) | Android equivalent |
|---|---|
| SwiftUI | Jetpack Compose |
| `BLEManager: ObservableObject` + `@Published` | `ViewModel` + `StateFlow` |
| `@EnvironmentObject` | shared `ViewModel` (or Hilt DI) |
| CoreBluetooth `CBCentralManager` | `BluetoothLeScanner` + `BluetoothGatt` |
| `Report.swift` (UIGraphicsPDFRenderer + CoreText) | `android.graphics.pdf.PdfDocument` + `Canvas` |
| `ShareSheet` / `UIActivityViewController` | `Intent.ACTION_SEND` + `FileProvider` |
| Keychain (password list) | `EncryptedSharedPreferences` (Jetpack Security) |
| Documents dir (report history) | app-specific `filesDir` / `MediaStore` |
| `Haptics` (`UIImpactFeedbackGenerator`) | `Vibrator` / `VibrationEffect` |
| `Info.plist` usage strings | `AndroidManifest.xml` + runtime permission prompts |
| `Assets.xcassets` app icon | `res/mipmap` adaptive icon |
| Xcode / XcodeGen | Android Studio / Gradle |
| deployment target iOS 17 | `minSdk` (≈ API 28) / `targetSdk` latest |

### BLE specifics (where Android differs and bites)
- **Permissions.** Android 12+ needs runtime `BLUETOOTH_SCAN` +
  `BLUETOOTH_CONNECT`; declare `BLUETOOTH_SCAN` with
  `android:usesPermissionFlags="neverForLocation"` to scan without location.
  Pre-12 needs `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION`.
- **Scan.** `startScan` with no service filter (encoder doesn't advertise its
  UUID); match by `ScanResult.device.name` — same strategy as iOS.
- **Enable notifications = two steps.** Unlike iOS, you must
  `setCharacteristicNotification(true)` **and** write `0x00002902` (CCCD)
  descriptor with `ENABLE_NOTIFICATION_VALUE`.
- **Serialized GATT ops.** Android requires you to wait for each
  `onCharacteristicWrite` callback before the next write — which actually maps
  cleanly onto our existing serial-write queue. The ~35 ms password pacing
  becomes "wait for the write callback, then small delay". Re-tune on-device.
- **Write type.** transparent-UART char is write+notify; pick
  `WRITE_TYPE_DEFAULT` vs `NO_RESPONSE` as on iOS.
- Default MTU (23) is fine for the short command lines.

### Dev environment & devices (important — read before the weekend)
- **No Mac needed.** Android Studio runs on Windows/Linux/Mac. Free.
- **The emulator has no Bluetooth** — exactly like the iOS Simulator. So a real
  Android phone is required to test anything past the UI.
- **Locked-down work phones (MDM) are usually a blocker.** Managed devices often
  disable Developer Options / USB debugging / sideloading entirely. Plan to
  develop and test on a **personal or spare Android device**. Deploying the
  finished app to managed work phones would go through the org's MDM / managed
  Google Play, not sideloading.

### Getting it on a device
1. Android Studio → enable **Developer Options + USB debugging** on the phone →
   **Run** (installs like Xcode's Run button).
2. Or build an **APK** and sideload: transfer the file, allow "install unknown
   apps", tap to install. No signing team, no provisioning profile, **no 7-day
   expiry**.
3. Wider distribution later: one-time **$25 Google Play** account → internal /
   closed testing tracks.

### Pre-handset prep checklist (can be done before any device arrives)
- Install **Android Studio** (free; Windows/Linux/Mac — no Mac needed). Let it
  install the SDK, **platform-tools** (`adb`), and an emulator.
- Create a throwaway **Empty Compose Activity** project and run it on the
  **emulator** to confirm the toolchain. (Emulator has **no Bluetooth** — UI /
  sanity only; the encoder needs a real device.)
- Learn the three Xcode-equivalents: **Run** button, **Logcat** (= Xcode
  console), **Device Manager** (run-target list).
- Ready the **logo** PNG and an **app icon** (Android adaptive icon = fore/back
  layers; a square PNG works to start).
- Confirm the incoming handset is **not itself MDM-locked** (so Developer Options
  can be enabled), note its **Android version**, and that it has Bluetooth.

### Enterprise distribution (company pushes to managed devices)
The deliverable for fleet deployment is a **signed APK/AAB** (built with a
release **keystore** kept safe — a debug build can't be distributed). IT deploys
remotely via their **MDM/EMM** (Intune, Workspace ONE, SOTI, …):
1. **Direct APK upload to the MDM** as a line-of-business app (simplest — hand IT
   one signed `.apk`), or
2. **Private app via Managed Google Play** (Android Enterprise): upload once,
   restrict to the org, IT pushes it (needs the $25 Play Console account).
Flag to IT early that the app needs **Bluetooth scan/connect permissions**, which
some MDM policies gate.

### Proof-of-concept scope (first weekend target)
Smallest end-to-end slice that proves the encoder talks to Android:
1. New Kotlin + Compose project; add BLE permissions + runtime request.
2. Scan (no filter) → list devices by name → tap to connect.
3. `connectGatt` → discover services → find transparent-UART service/char by UUID.
4. Enable notifications (setNotify + CCCD write).
5. Serialized paced write of a password (CR+LF, byte-by-byte) on the
   `Password:` prompt; detect `Welcome to Shire` → "logged in".

If that slice works, the rest (command grids, presets, report) is
straightforward re-expression of the iOS logic. Everything above stays
**on-device, no network**, matching iOS.
