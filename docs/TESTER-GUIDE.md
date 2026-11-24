# Tester guide

You do not need a computer or adb. Each check is a tap, a look at the screen, and **Copy report** if something looks wrong. Paste the report as it is.

Samsung Auto Blocker (One UI 6+, Settings → Security and privacy → Auto Blocker) blocks APKs from outside the Galaxy Store. Turn it off to install, then you can turn it back on. Installing needs the pre-release APK from GitHub Releases.

Shizuku, without root, has to be started again after every reboot. It pairs through its own notification: Developer options → Wireless debugging → Pair device with pairing code. That needs Wi-Fi. It does not need an internet connection or a PC. Some One UI builds turn Wireless debugging off when Wi-Fi changes, and then Shizuku has to be started again too.

## A. Bridge app only

1. Install the APK, open it, and allow notifications. A quiet "S Pen bridge" notification appears. Diagnostics says the server is listening on 127.0.0.1:47631.
2. Install Shizuku, start it, and tap **Grant Shizuku access**. Diagnostics shows Shizuku installed, running, uid 2000, and permission granted.
3. Diagnostics → **Self-test**. It should find a pen node (often `sec_e-pen`) and say read access is ok while you hover. If it says `selinux-denied`, `EACCES`, or `zero-bytes`, stop and send the report. This firmware cannot run the bridge.
4. When self-test asks, press and release the side button. It should learn a key code, usually `0x14b`. Another code is fine; include it in the report.
5. Press and release the side button 10 times with the app open. The press count goes up by 10, and the latency is shown. A small number, about 0–5 ms, is what we hope for.
6. The loopback handshake line is ok. That checks the server without Obsidian.
7. **Copy report** and paste it even when everything passed.

## B. With Obsidian

8. Copy the pairing code in the bridge, paste it in the Excalidraw Stylus Controls plugin, and check that the fingerprints match. Bridge Diagnostics shows 1 authenticated client.
9. Press the side button in Obsidian. The plugin status shows the press.
10. From the notification shade, tap **Open menu**. The menu opens in Obsidian.

## C. After a restart

11. Restart the tablet. Without opening the bridge, the notification comes back and says the pen is waiting for Shizuku. Start Shizuku. Within a few seconds Diagnostics shows the pen is ok. You should not have to tap anything else.
12. Stop Shizuku while holding the side button in Obsidian. The plugin says the pen is unavailable, and the eraser does not stay stuck.
13. **Reset pairing** in the bridge. The plugin says the pairing code is wrong. Pair again and check the new fingerprint.
14. Optional: leave it overnight and, in the morning, check Settings → Battery for this app's usage. Report the percentage.

The report never includes the pairing code, a vault path, or touch coordinates. It does include the device model and One UI version, which we need for the compatibility table.
