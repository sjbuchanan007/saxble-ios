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

## Open decisions (need a call before building)
1. Password storage: **Keychain** (recommended) vs UserDefaults?
2. **Redact the password** in saved/shared PDFs (and transcript)? Recommended: yes.
3. History grouping key: **Location with name/UUID fallback** (recommended) — OK?
4. Save trigger: explicit only first, or also **auto-save on logout**?
5. Build order: which feature first?

---

## Also on the list
- **Editable presets in-app** (currently `Presets.swift` is compile-time).
