# S Pen Companion Bridge: pragmatic architecture plan

## Status after review (November 2026)

Source review plus host tests, not a tablet run. The app can write one signed `open-menu` file and can show native button bits while it is the foreground screen. It does not keep a listener after you leave. The reboot switch opens the screen once; it is not a resident service. A resident listener would not receive the barrel button while Obsidian is in front. See [docs/DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

## Decision

Build a small Android companion for Samsung S Pen **Air Action** events and a
signed file bridge to this Obsidian plugin.

This can reliably deliver explicitly configured remote commands such as **open
the stylus menu**. It cannot safely implement **barrel held while drawing =
temporary eraser** until Obsidian exposes the native Android `MotionEvent`
data to plugins, unless a device-specific Samsung remote API can be proven to
deliver the same in-contact signal to a companion while Obsidian is foreground.

## Why this is necessary

The plugin runs as JavaScript inside Android System WebView. The tested device
reports `buttons: 1` while the pen is in normal contact, rather than exposing a
distinct barrel-button state. Native Android `MotionEvent` data and Samsung's
S Pen Remote SDK are not available to a Community Plugin and cannot be enabled
by changing Obsidian's Android manifest.

A companion cannot observe ordinary pen contact events that Android sends to
Obsidian. Samsung remote/Air Action events may be available to the companion,
but must be tested while Obsidian owns contact; a foreground diagnostic app
does not prove background delivery. The scope therefore deliberately targets
menu and named actions, not automatic temporary tool switching.

## V1 scope

- One Android companion app, initially distributed privately.
- Samsung-only Air Action support when the device and S Pen support it.
- One action: open the plugin's stylus menu at the latest pen position, with a
  safe fixed fallback position.
- Later optional actions: copy selection, paste at last pen position, or a
  small allowlisted set of named Obsidian commands.
- No automatic eraser, Accessibility Service, screen overlay, global input
  capture, broad-storage permission, or network service.

## Architecture

```text
S Pen Air Action
  -> Android companion (Samsung Remote SDK)
  -> signed JSON event in selected Obsidian vault
  -> plugin bridge validates and consumes event
  -> existing action/menu dispatcher
```

### 1. Android companion

Use Kotlin and isolate Samsung integration behind a capability check.

- Check device/S Pen/Remote SDK availability before exposing setup controls.
- Let the user map one supported Air Action to one bridge action.
- Keep a monotonically increasing event sequence number.
- Store the pairing secret in Android Keystore-backed encrypted preferences.
- Ask the user to select the vault root with Android's Storage Access
  Framework (SAF) folder picker; request no broad storage permission.
- Provide diagnostics: SDK availability, S Pen connection state, last remote
  event, last bridge write result, and a **Send test menu event** button.

### 2. Vault-file bridge

Use the selected vault as narrow local transport:

```text
.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json
```

- Write a complete event to a temporary document and rename it when the SAF
  provider supports that operation. Otherwise replace the whole document only
  after calculating its checksum.
- Poll only while an Excalidraw view is active (target: every 150 ms), and use
  vault-file change notifications where available.
- On acceptance, record the sequence number and atomically replace the event
  with an acknowledgement, or clear it.
- Never send drawing geometry, note content, clipboard content, or arbitrary
  command strings across this bridge.

### 3. Plugin bridge module

Add a dedicated bridge module. It must stay separate from Pointer Event
normalization and stylus gesture policy.

- Read the pending envelope only while a compatible Excalidraw view is active.
- Validate its schema version, source/app identifier, age, sequence monotonicity
  and HMAC before dispatch.
- Dispatch only a fixed action enum to the existing action dispatcher.
- Resolve the active Excalidraw view when dispatching; drop the event with a
  diagnostic reason if no compatible view exists.
- Show a compact notice only for setup errors. Keep detailed bridge diagnostics
  opt-in.

## Event envelope

```json
{
  "version": 1,
  "deviceId": "random-install-id",
  "sequence": 42,
  "issuedAtMs": 1730000000000,
  "action": "open-menu",
  "signature": "base64url-hmac-sha256"
}
```

Validation rules:

- `version` is exactly `1`.
- `action` is one of `open-menu`, `copy-selection`, or
  `paste-at-last-pen-position`.
- The event is at most five seconds old.
- `sequence` exceeds the last accepted sequence for that `deviceId`.
- The HMAC covers canonical JSON fields except `signature`.
- Invalid, stale, duplicate, or unauthenticated events are dropped with no
  visible action.

## Pairing and security

1. The plugin creates a random shared secret.
2. It displays the secret once as a QR code or copyable pairing code.
3. The user confirms pairing in both the Android companion and plugin.
4. The companion stores the secret in encrypted storage; the plugin stores it
   in its normal plugin data.
5. Resetting pairing rotates the secret and invalidates all queued events.

Treat the vault as untrusted transport. Every event must be authenticated and
strictly allowlisted. Keep logs local, redact signatures, collect no telemetry,
and require neither network nor Accessibility/overlay permissions.

## Delivery plan

### Phase 0 — feasibility (1–2 days)

- Create a Kotlin diagnostic app that records native `MotionEvent` button state
  while drawing.
- Verify a distinct side-button state during contact, not merely `buttons: 1`
  normal contact.
- Run a foreground-service Samsung Remote SDK spike while Obsidian owns the
  stylus contact. Verify it receives matching barrel down/up events, rather
  than only remote/Air Action clicks.
- Stop eraser-bridge work if either proof fails; do not expand plugin
  complexity before both checks pass.

### Phase 1 — secure end-to-end bridge (3–5 days)

- Implement pairing, HMAC signing, event sequence, pending/ack protocol, and
  companion diagnostics.
- Implement the plugin bridge with deterministic envelope validation tests.
- Add an opt-in bridge diagnostics panel and the test-event control.

### Phase 2 — limited beta (3–5 days)

- Test menu opening across supported Samsung devices and Android versions.
- Test duplicate, stale, modified, malformed, and delayed files.
- Confirm that inactive/non-Excalidraw views never receive an action.
- Add one optional action only after the first action is dependable.

### Phase 3 — product decision

If temporary eraser while drawing remains required, request or implement a
native Obsidian-side API that exposes normalized stylus `MotionEvent` button
data to plugins. A companion bridge is not a substitute for this native input
path.

## Alternatives rejected

| Alternative | Reason |
| --- | --- |
| More Pointer Event masks/profiles | The hardware trace proves `buttons: 1` is ordinary contact, so it cannot distinguish the barrel button. |
| WebHID/Web Bluetooth | Not a dependable capability in Android WebView and does not grant access to S Pen input. |
| Local HTTP/WebSocket server | Adds network and lifecycle/security cost without solving native S Pen access. |
| Accessibility or overlay capture | Too invasive, fragile, and inappropriate for the narrow command use case. |
| Automatic eraser via companion | Impossible to synchronize safely with in-contact pen input; explicitly out of scope. |

## Success criteria

- The companion can emit a signed test `open-menu` action into the selected
  vault without broad storage or network permissions.
- The plugin accepts it only once, only while a supported Excalidraw view is
  open, and opens its menu predictably.
- Invalid, replayed, stale, or modified events have no effect.
- The implementation preserves existing Pointer Event diagnostics and does not
  reintroduce experimental barrel profiles that conflict with the hardware
  evidence.
