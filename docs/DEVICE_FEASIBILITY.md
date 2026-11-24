# Device feasibility

Reviewed in November 2026 from Samsung’s S Pen docs, Android’s input and storage docs, the Pointer Events specification, and current Chromium source. This repo has not been run on a tablet. Unit tests and a debug assemble check the code; they do not prove a Samsung build.

## Verdict

Air Actions and the S Pen Remote SDK still deliver only to the foreground app. That limit is unchanged, and it is why a resident notification cannot see the barrel button by itself.

The evdev node is a different route. `/dev/input/eventN` is group `input`, and the ADB shell user is in that group. Shizuku runs a UserService as that user, so the bridge can read `EV_KEY BTN_STYLUS` while Obsidian is in front, without grabbing the device. This works only when the vendor SELinux policy lets `shell` read `input_device`. One OPPO build returned zero bytes. The diagnostics access probe is how a tester report answers that for a given One UI version. If the probe says `selinux-denied`, `EACCES`, or `zero-bytes`, the plugin stays on the hover-latch fallback.

## What should work on a device

- Choose a vault folder with the system picker. The app stores a persistable tree grant. It requests `INTERNET` so the loopback socket can bind, and it does not request storage, overlay, or accessibility permissions.
- **Send signed test** writes `.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json`. The write goes to `pending-incoming.json` first. That name ends in `.json` because Android’s `FileUtils.splitFileName` appends the MIME extension when the display name does not already match `application/json`.
- **Copy pairing code** puts the local 32-byte secret on the clipboard as base64url. The plugin uses that token on the localhost stream. The status line does not print the secret.
- With **Keep the bridge running** on, leaving the app leaves a quiet “S Pen bridge” notification. The notification action **Open menu** writes the vault file and pushes the same signed JSON to every connected plugin. After a restart the boot receiver starts the service again. **Allow unrestricted battery** is the Samsung step that stops the system from freezing that service.
- Drawing on this screen logs action, tool, button bits, and pressure. Coordinates are omitted. A barrel press that the digitizer reports includes `STYLUS_PRIMARY` (`MotionEvent.BUTTON_STYLUS_PRIMARY`, value 32). Normal tip contact often has button state `none`, because Android does not set `BUTTON_PRIMARY` for a plain stylus touch.
- Air Action `F8` is handled on key down while this activity is in front. Samsung leaves new apps off until you enable them under S Pen → Air actions. There is no `enable_key`; Samsung issues that only on request, so the action is not on by default.

## What should fail, and that failure is the result

- Air Actions and the Remote SDK still do not deliver the barrel button while Obsidian is in front. The evdev reader is the path that can. If the access probe is denied, that press still does not reach the plugin, and the report says why.
- With **Keep the bridge running** off, a restart does not start this process. The manifest receiver is disabled.
- `obsidian://` from the background service is a worse channel. Android’s [background activity launch rules](https://developer.android.com/guide/components/activities/background-starts) can block it. The plugin opens the localhost connection itself, so no activity has to be started.
- An Obsidian vault in app storage (`Android/data/md.obsidian/...`) cannot be granted. Android 11+ also blocks choosing the storage root or the Download directory itself. Use a device-storage vault and select that vault folder. See [Obsidian for Android](https://obsidian.md/help/android) and the [Storage Access Framework limits](https://developer.android.com/training/data-storage/shared/documents-files).

## Why the WebView lost the barrel button

The digitizer bit and the DOM bit are different:

| State | Android `getButtonState()` | Pointer Events `buttons` |
| --- | --- | --- |
| Hover, no button | `0` | `0` |
| Barrel held | `BUTTON_STYLUS_PRIMARY` (32) | `2` (barrel) |
| Tip on glass | usually `0` (contact is the action, not `BUTTON_PRIMARY`) | `1` (pen contact) |
| Tip and barrel | `32` when the digitizer reports the barrel | `3` |

[Pointer Events Level 4](https://www.w3.org/TR/pointerevents4/) assigns pen contact to bit 0 (value 1) and the pen barrel to bit 1 (value 2). [Android’s `MotionEvent`](https://developer.android.com/reference/android/view/MotionEvent) keeps the barrel in `BUTTON_STYLUS_PRIMARY` and says `BUTTON_PRIMARY` is not set for a simple stylus tip touch.

Chromium’s `ToWebPointerButton` in `ui/events/blink/blink_event_util.cc` maps `BUTTON_STYLUS_PRIMARY` to `kLeft`, the same value it uses for primary contact. That mapping was still on Chromium main during this review. Firefox’s 2026 change for bug 2082727 describes the Chrome-on-Android result: a stylus tap with the barrel held cannot be told apart from a tap without it, because both become `buttons` value 1.

The stylus plugin already ignores contact-only `buttons: 1` for that reason. This companion cannot repair an event Obsidian’s WebView has already flattened.

## Why a second app cannot watch that stream

[Samsung’s Air Actions guide](https://developer.samsung.com/galaxy-spen-remote/air-actions.html) says the framework receives the BLE event, checks the foreground app, and only then delivers a `KeyEvent`. The supported gestures are click, double-click, and a few air motions. They are discrete actions, not a level that stays down for the length of a stroke. `version="1.2"` is mandatory on the remote-actions resource; this app sets it.

The [S Pen Remote SDK FAQ](https://developer.samsung.com/galaxy-spen-remote/faq.html) answers “Can S Pen events be received in the background?” with: it is restricted to the foreground activity, and listeners should be unregistered when the activity is paused. The SDK is also an old, device-specific library. It is not integrated here, because the same foreground rule would still apply while Obsidian is open.

`MONITOR_INPUT` is not a permission a normal app can hold. An accessibility service does not receive `MotionEvent.getButtonState()`. An overlay that receives the touch takes the touch away from Obsidian.

## Power

The process is resident while **Keep the bridge running** is on. LibrePods uses the same Android mechanism: a foreground service with `foregroundServiceType="specialUse"`, `START_STICKY`, and a low-importance ongoing notification. Android requires that notification; it has no sound and no vibration. Idle work is one localhost accept loop and a 20-second comment frame only while a plugin is connected. There is no wake lock and no sensor.

`BOOT_COMPLETED` may start this service type. Android 15 blocks several other foreground-service types from that broadcast, not `specialUse`. See [foreground service background-start limits](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Samsung can still freeze the process. **Allow unrestricted battery** asks the system to leave it alone. That still does not deliver the S Pen button into this process.

## What would actually fix the eraser

The WebView needs `BUTTON_STYLUS_PRIMARY` reported as Pointer Events barrel (`buttons` value 2), including together with contact (`buttons` value 3). Obsidian exposing native `getButtonState()` to plugins would do the same job. The stylus plugin already handles those values. A file dropped in the vault cannot be lined up with a stroke that has already started.
