# Device feasibility

Reviewed in November 2026 from Samsung’s S Pen docs, Android’s input and storage docs, the Pointer Events specification, and current Chromium source. This repo has not been run on a tablet. Unit tests and a debug assemble check the code; they do not prove a Samsung build.

## Verdict

Use this app as a screen you open, not as a process that stays alive.

It should be able to write one signed `open-menu` file into a device-storage Obsidian vault, and it can show native stylus button bits for a pen that is touching this screen. The switch **Open this screen after the tablet restarts** is off until you turn it on. It asks Android to open this screen once after unlock. It does not leave a listener running.

A resident listener is worse. Samsung delivers S Pen button events and Air Actions only to the resumed foreground app, and its Remote SDK FAQ says those listeners must be unregistered when the activity pauses. A foreground service, a sticky background service, or a process kept in “Never sleeping” is still not that resumed app. Obsidian would keep the pen, this process would stay awake, and the barrel button would still be missing inside the WebView.

Holding the barrel button while drawing in Obsidian cannot become a temporary eraser through this companion.

## What should work on a device

- Choose a vault folder with the system picker. The app stores a persistable tree grant and requests no broad storage, network, overlay, or accessibility permission.
- **Send signed test** writes `.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json`. The write goes to `pending-incoming.json` first. That name ends in `.json` because Android’s `FileUtils.splitFileName` appends the MIME extension when the display name does not already match `application/json`.
- **Copy pairing code** puts the local 32-byte secret on the clipboard. The plugin does not read the file yet, and this app does not show the secret in the status line.
- Drawing on this screen logs action, tool, button bits, and pressure. Coordinates are omitted. A barrel press that the digitizer reports includes `STYLUS_PRIMARY` (`MotionEvent.BUTTON_STYLUS_PRIMARY`, value 32). Normal tip contact often has button state `none`, because Android does not set `BUTTON_PRIMARY` for a plain stylus touch.
- Air Action `F8` is handled on key down while this activity is in front. Samsung leaves new apps off until you enable them under S Pen → Air actions. There is no `enable_key`; Samsung issues that only on request, so the action is not on by default.

## What should fail, and that failure is the result

- Pressing the barrel button while Obsidian is in front does not update this app and does not write a file. Air Actions are routed to the foreground app. The Remote SDK is also limited to the foreground activity.
- After you leave this screen, the process is eligible to stop. Nothing is scheduled.
- With the reboot switch off, a restart does not start this process. The manifest receiver is disabled.
- With the switch on, Android can still refuse to open the activity. Boot is not one of the [background activity launch exemptions](https://developer.android.com/guide/components/activities/background-starts). Logcat from `ActivityTaskManager` reports a blocked background activity launch. The receiver then exits.
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

There is no foreground service, wake lock, alarm, or poll. [Android’s foreground-service rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) are a reason to avoid one, and they would not grant pen events anyway. `BOOT_COMPLETED` is allowed as a manifest broadcast ([implicit broadcast exemptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)) and is still one short start, not a resident.

Leaving the app in Samsung’s “Never sleeping” list would keep a process that cannot see the pen. The reboot switch does not require that.

## What would actually fix the eraser

The WebView needs `BUTTON_STYLUS_PRIMARY` reported as Pointer Events barrel (`buttons` value 2), including together with contact (`buttons` value 3). Obsidian exposing native `getButtonState()` to plugins would do the same job. The stylus plugin already handles those values. A file dropped in the vault cannot be lined up with a stroke that has already started.
