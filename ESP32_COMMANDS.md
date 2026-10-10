# Jack ⇄ ESP32 Command Reference

Everything the Jack app sends to the ESP32, and what it expects back.
Use this when upgrading the ESP32 firmware. **Update this file whenever a command is added or changed.**

Last updated: Jack v1.7 (2026-10-10)

## Connection

| | |
|---|---|
| **BLE** | Nordic UART Service (NUS). Device name should contain `ESP32` (currently `ESP32_Device`). |
| Service UUID | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (Jack → ESP32, write) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` — write-without-response if supported, else write |
| TX (ESP32 → Jack, notify) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` — notify (or indicate) |
| MTU | Jack requests 247 → lines up to 244 bytes per packet |
| Advertising | Firmware must **restart advertising after a disconnect**, or Jack can't reconnect. |
| **USB serial** | 115200 baud, 8N1 (CP210x / CH340 / FTDI chips) |
| Framing | Plain text, **one command per line, ending with `\n`** (both directions). |

## Jack → ESP32 (commands the firmware should handle)

| Command | When Jack sends it | Firmware status | Suggested firmware action |
|---|---|---|---|
| `ATTENDANCE:<id>` | Someone checks **in** (ID + selfie) | ✅ existing | e.g. door unlock / welcome beep / LED |
| `CHECKOUT:<id>` | Someone checks **out** (ID + selfie) — added v1.6 | 🆕 **not yet in firmware** | e.g. goodbye beep / log exit |
| `PING` | Operator presses **Volume Down** | ✅ existing (assumed) | Reply with a line, e.g. `PONG`, so Jack's log confirms the link |
| `CHARGE` | Tablet battery ≤ minimum threshold (default 20 %) | ✅ existing | Switch charger relay **ON** |
| `DISCHARGE` | Tablet battery ≥ maximum threshold (default 80 %) | ✅ existing | Switch charger relay **OFF** |

Notes:
- `<id>` is the Attendee ID typed on the keypad (letters/digits, as registered in Jack).
- `CHARGE` / `DISCHARGE` are sent once per threshold crossing, not repeatedly.
- Jack doesn't wait for a reply to any command (fire-and-forget).

## ESP32 → Jack (messages)

- Any text line. Every line appears in Jack's terminal log.
- **Alerts are user-defined in Jack:** each alert has a trigger phrase; any line *containing* it (case-insensitive) plays that alert's sound and wakes the screen. Nothing is hard-coded — e.g. send `MOTION DETECTED!` and create an alert with trigger `motion detected`.
- Several alerts can match one line; their sounds play one after another.
- Lines should end with `\n`. A line without one is still delivered after ~0.3 s of silence.

## Firmware to-do

- [ ] Handle `CHECKOUT:<id>`
- [ ] Reply to `PING` (e.g. `PONG`) if not already
- [ ] Confirm advertising restarts after every disconnect
