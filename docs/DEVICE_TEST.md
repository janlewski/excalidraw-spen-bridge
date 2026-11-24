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
| Leave the app | Switch to Obsidian and click the barrel, including while drawing. | This app’s log does not grow. No new file is written from that press. |
| Reboot switch off | Leave the switch off and restart the tablet. | This process does not start. |
| Reboot switch on | Turn the switch on, restart, unlock. | This screen opens once, and the status says it opened after restart. If it does not, `ActivityTaskManager` in logcat reports a blocked background activity launch. No notification or service remains. |
| Plugin effect | Send a test with an Excalidraw note open. | Obsidian does not open the stylus menu. The plugin does not consume the file yet. |

## Do not “fix” a blocked reboot by keeping the app awake

Samsung’s Sleeping apps list can swallow `BOOT_COMPLETED`. Allowing the boot broadcast is enough. Putting this app in Never sleeping keeps a process that still cannot see the pen while Obsidian is open. Do not add a foreground service to get around a blocked activity launch.
