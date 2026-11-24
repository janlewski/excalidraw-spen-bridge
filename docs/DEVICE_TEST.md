# Device test

The tester-facing steps are [TESTER-GUIDE.md](TESTER-GUIDE.md). This page is the same checklist, with what each step proves. Copy the diagnostics report whenever a step looks wrong, and also when the smoke test passes.

## A. Bridge app

1. The notification appears and Diagnostics says `127.0.0.1:47631` is listening. That proves the loopback socket can bind, which requires the `INTERNET` permission.
2. Shizuku is installed, running, uid 2000, and this app is permitted.
3. Self-test finds the pen node and read access is ok while hovering. `selinux-denied`, `EACCES`, or `zero-bytes` means this firmware cannot run the evdev route. Send the report and stop.
4. Learn button reports a key code. `0x14b` is `BTN_STYLUS`. Another code is acceptable.
5. Ten presses increment the counter. `sent - t` is ideally 0–5 ms. A huge value means the evdev clock is not `CLOCK_REALTIME`.
6. The loopback handshake succeeds without Obsidian.
7. Paste the report even on a pass. It is the compatibility record.

## B. Obsidian

8. Pairing fingerprints match, and Diagnostics shows one authenticated client.
9. A side-button press in Obsidian shows up in the plugin. The button is delivered while Obsidian is in front.
10. The notification **Open menu** action opens the plugin menu.

## C. Lifecycle

11. After reboot the service returns with "Pen: waiting for Shizuku" without opening the app. Starting Shizuku rebinds on its own.
12. Stopping Shizuku while the button is held does not leave the eraser stuck.
13. Reset pairing closes the plugin with a wrong-code status. Re-pairing uses the new fingerprint.
14. Optional overnight battery note.

Drawing cases (press before contact, mid-stroke split, release until lift, quick press) are in the plugin tester guide.
