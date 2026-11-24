# Event contract

The companion and `excalidraw-stylus-controls` share one signed envelope. The plugin side is not implemented yet. Until it validates this envelope and shares the pairing secret, neither channel changes Obsidian.

## Localhost stream

While the bridge service is running it listens only on `127.0.0.1:47631`.

- `GET /health` returns `{"ok":true}` with no token.
- `GET /events?token=<pairing-code>` stays open as `text/event-stream`. The token is the base64url pairing code from **Copy pairing code**, with no padding.
- Each command is one SSE record: `data: ` plus the signed JSON object, then a blank line.
- A `: ping` comment every 20 seconds keeps the connection open while a plugin is connected.
- A wrong token gets HTTP 401 and the socket closes.

The plugin opens this connection itself while an Excalidraw view is active. That works with Obsidian in front, because the plugin makes the request. An `obsidian://` intent from the service would be a background activity launch and Android can block it.

The vault file below is the same JSON, written as well, so a plugin that cannot open localhost can still poll.

## Path

Relative to the vault folder the user selected:

```text
.obsidian/plugins/excalidraw-stylus-controls/spen-bridge/pending.json
```

`pending-incoming.json` in that same directory is a temp file. Readers ignore it. The mailbox is one slot: a newer write replaces `pending.json`. A slow reader can miss an older sequence. Gaps are allowed. A repeated sequence is rejected.

The file must not contain drawing geometry, note text, clipboard contents, or a free-form command string.

## Envelope

```json
{
  "action": "open-menu",
  "deviceId": "8c1a0e3e-1b2c-4d5e-9f00-123456789abc",
  "issuedAtMs": 1730000000000,
  "sequence": 42,
  "version": 1,
  "signature": "TbL_EvrNehm94SHcm6MQ1ecEIiDyhnNimy1HhJmKdV4"
}
```

`version` is `1`. `action` is `open-menu` only. `deviceId` is a random UUID created on first use and kept in app preferences. `sequence` starts at 1 and increases by one for each successful write. `issuedAtMs` is the companion’s `System.currentTimeMillis()`.

## Canonical bytes

The HMAC input is UTF-8, no BOM, no extra whitespace, fields in this order, signature excluded:

```text
{"action":"open-menu","deviceId":"device","issuedAtMs":1730000000000,"sequence":42,"version":1}
```

Strings use this encoding:

- Wrap in `"`
- Escape `"` and `\` with a backslash
- Escape code points below `U+0020` as `\u00xx` with lowercase hex
- Leave every other character as raw UTF-8

Numbers are decimal integers with no leading zeros. The signature is base64url (RFC 4648, URL alphabet, no padding) of HMAC-SHA-256 over those exact bytes. The published file is the canonical object plus `,"signature":"..."` before the closing brace. Parsers rebuild the canonical string from the fields. They do not HMAC the raw file bytes.

The vector above with secret bytes `secret` (the five ASCII characters, not a 32-byte key) signs as `TbL_EvrNehm94SHcm6MQ1ecEIiDyhnNimy1HhJmKdV4`. Production secrets are 32 random bytes.

## Checks before any action

Apply all of them. On any failure, drop the event and do not open a menu.

- `version` is exactly `1`
- `action` is exactly `open-menu`
- `issuedAtMs` is within 5000 ms of the plugin clock (`BridgeEvent.MAX_AGE_MS`)
- `sequence` is greater than the last accepted sequence for that `deviceId`
- HMAC matches the canonical bytes and the paired secret

Accepting an event records its sequence, then clears or replaces the file so it cannot fire again.

## Secret

The companion wraps the 32-byte secret with a non-exportable Android Keystore AES-GCM key. A failed decrypt does not mint a new secret. Clearing app storage is the reset.

The plugin must get that secret from the **Copy pairing code** button (base64url, no padding) and store it in its own plugin data. The vault is the transport. The HMAC stops a truncated or unauthenticated file from dispatching. It is not a boundary against someone who can already read the plugin data, and the secret will sync if that plugin data syncs. Do not reuse it for anything sensitive.

The companion does not yet rotate the secret from a plugin-side reset.
