# S Pen → Obsidian Excalidraw Bridge

Private Android companion for Samsung tablets. It writes a signed, allowlisted command into an Obsidian vault for the `excalidraw-stylus-controls` plugin to consume.

## Current scope

- Kotlin Android app; min SDK 26, target/compile SDK 36.
- Vault selection through Android's Storage Access Framework: no broad storage, network, overlay, or accessibility permissions.
- A sticky foreground service, on by default, with a quiet ongoing notification. It comes back after reboot. **Allow unrestricted battery** is there because Samsung otherwise freezes background work. Idle cost is a localhost accept loop, not a sensor or a wake lock.
- The plugin connects to `http://127.0.0.1:47631/events?token=<pairing-code>` and receives server-sent events. The same signed JSON is also written into the vault.
- Keystore-wrapped local pairing secret; HMAC-SHA-256 event envelopes; monotonic sequence values. **Copy pairing code** puts the secret on the clipboard for the plugin.
- A manual signed `open-menu` test, a notification action with the same effect, a native button log while this screen is in front, and a Samsung Air Actions KeyEvent declaration (F8).
- Writes `.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json`.

This stays running so the plugin has a channel while Obsidian is open. It is still **not** an automatic barrel-button eraser. Samsung delivers Air Actions and Remote SDK events to the foreground app only, so the resident process does not see the barrel button while Obsidian is on screen. Details are in [docs/DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

## Pairing contract still required in the plugin

The plugin must expose a user-confirmed pairing flow and persist the same 32-byte secret. It must validate `version`, fixed action enum, HMAC over canonical JSON, a five-second age limit, and sequence monotonicity before dispatching only when a compatible Excalidraw view is active.

The companion generates its secret locally. **Copy pairing code** is the handoff until the plugin can show its own code and both sides can rotate it. The wire format is [docs/EVENT_CONTRACT.md](docs/EVENT_CONTRACT.md). The tablet checklist is [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md).

## Build

Open in Android Studio (JDK 17), or from this directory:

```sh
./gradlew testDebugUnitTest assembleDebug
```

## Current platform research (November 2026)

- Android's [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) supports durable, user-granted directory access through `ACTION_OPEN_DOCUMENT_TREE`; Android 11+ restricts selecting storage roots, Downloads, and other apps' private directories.
- The [Android crypto guidance](https://developer.android.com/privacy-and-security/cryptography) recommends AES-GCM and Android Keystore when higher key protection is needed; AndroidX `EncryptedSharedPreferences` is now deprecated, so this app wraps its ordinary preferences payload with a non-exportable Keystore AES-GCM key instead.
- Samsung's [Air Actions guide](https://developer.samsung.com/galaxy-spen-remote/air-actions.html) says the framework converts BLE gestures to KeyEvents and checks the **foreground app** before delivery. This is useful for a foreground diagnostic/control surface, not evidence for background interception.
- Samsung's [S Pen Remote SDK](https://developer.samsung.com/galaxy-spen-remote/overview.html) remains version 1.0.2 from 2019 and is explicitly device/framework dependent. Its FAQ limits events to the foreground activity, so it is not what keeps this process alive.
- The resident process follows LibrePods: a sticky [foreground service](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) and a low-importance notification. The type is `specialUse`, which boot is still allowed to start. It keeps the localhost channel up. It does not receive another app's pen input.
