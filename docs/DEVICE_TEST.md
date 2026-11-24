# Device test

Run this on the Samsung tablet before treating any S Pen claim as confirmed. Host checks first:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Record the device model, Android version, One UI version, S Pen model if shown, Obsidian version, Excalidraw version, and this app’s `versionName`.

Install the debug build. Use an Obsidian vault stored in device storage, not app storage. In the app, choose that vault folder itself. Do not choose Internal storage or the Download folder; Android 11+ rejects those roots.

## Cases

| Case | Steps | Expected |
| --- | --- | --- |
| Signed write | Choose the vault, send the test. | `pending.json` exists at the path in [EVENT_CONTRACT.md](EVENT_CONTRACT.md). It is one JSON object with `version` 1 and `action` `open-menu`. |
| Pairing code | Copy the pairing code, then send again. | The clipboard gets one base64url string. The status line does not print the secret. The new file’s `sequence` is higher. |
| Native barrel bit | With this app open, hover, hold the barrel, touch the glass, lift. | The log shows `STYLUS` and `STYLUS_PRIMARY(0x20)` when the barrel is held, including during contact if the digitizer reports it. Tip-only contact is often `buttons=none`. No coordinates are logged. |
| Air Action | Enable this app’s Air action in S Pen settings. Leave this screen in front. Click the side button once. | One new `open-menu` file. A second click gets the next sequence. |
| Leave the app | Switch to Obsidian and click the barrel, including while drawing. | The quiet notification remains. This app’s button log does not grow, and the barrel press does not write a file. |
| Notification command | With Obsidian in front, pull the shade and tap **Open menu**. | `pending.json` updates. A plugin connected to `127.0.0.1:47631` receives the same JSON. |
| Keep running | Leave the switch on, leave the app, restart, unlock. | The notification comes back without opening the screen. If Samsung froze the app, allow unrestricted battery and repeat. |
| Switch off | Turn **Keep the bridge running** off and restart. | The notification is gone and the process does not start. |
| Plugin effect | Send a test with an Excalidraw note open. | Obsidian does not open the stylus menu. The plugin does not consume the file yet. |

## Battery

Unrestricted battery keeps the service from being frozen. It does not make the S Pen button arrive while Obsidian is open. The notification is required by Android for a foreground service; its channel is low importance and silent.
