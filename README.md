# S Pen → Obsidian Excalidraw Bridge

Private Android companion for Samsung tablets. It writes a signed, allowlisted command into an Obsidian vault for the `excalidraw-stylus-controls` plugin to consume.

## Current scope

- Kotlin Android app; min SDK 26, target/compile SDK 36.
- Vault selection through Android's Storage Access Framework: no broad storage, network, overlay, or accessibility permissions.
- No resident listener, foreground service, wake lock, or poll. After you leave the screen, nothing in this app is scheduled.
- Keystore-wrapped local pairing secret; HMAC-SHA-256 event envelopes; monotonic sequence values. **Copy pairing code** puts the secret on the clipboard for the plugin.
- A manual signed `open-menu` test, a native button log while this screen is in front, and a Samsung Air Actions KeyEvent declaration (F8).
- Optional **Open this screen after the tablet restarts**. The boot receiver stays disabled until that switch is on, and Android can still block the launch.
- Writes `.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json`.

This is deliberately **not** an automatic barrel-button eraser, and it is deliberately **not** a background pen listener. Samsung delivers Air Actions and Remote SDK events to the foreground app only. A process that stays alive after reboot still does not see the barrel button while Obsidian is open. Details are in [docs/DEVICE_FEASIBILITY.md](docs/DEVICE_FEASIBILITY.md).

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
- Samsung's [S Pen Remote SDK](https://developer.samsung.com/galaxy-spen-remote/overview.html) remains version 1.0.2 from 2019 and is explicitly device/framework dependent. Keep it behind an optional capability boundary until a real-device spike proves it works on the target Tab and OS.
- Android 12+ limits background foreground-service starts, and Android 14+ adds service-type permission checks. Do not add a service merely to chase pen input; see Android's [current restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
