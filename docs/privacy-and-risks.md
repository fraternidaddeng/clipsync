# ClipSync · Read Before Use: Privacy and Risks

**English** | [简体中文](privacy-and-risks.zh-CN.md) | [日本語](privacy-and-risks.ja.md)

This page is written for people who use ClipSync, not for engineers. It answers three questions: where the things you copy go, who can see them, and what you should keep an eye on yourself. Each section first says what the app does, then what you can do. Engineering details are linked at the end.

Last updated: 2026-09-03.

## 1. Where your content goes

Text or an image you copy on one device travels directly over your own local network (or a Tailscale network) to the other paired device. The connection is encrypted, and each device only accepts the certificate fingerprint it memorized during pairing. There is no server and no account in between; neither the developer nor any third party can see the content.

When the two devices cannot reach each other, the content stays on the device that copied it and catches up after they reconnect.

## 2. A paired device is fully trusted

Once pairing completes, the other device receives every text and image you copy from then on, until you pause, turn on private mode, or revoke the pairing. The same applies in the other direction.

Revoking a pairing, deleting an entry, and clearing history all act on the local device only. Copies the other device already received stay there; ClipSync has no way to delete them remotely.

What you can do: pair only your own devices, or devices of people you fully trust. Revoke the pairing (Conduit → device management) before a device is lost, sent for repair, or handed to someone else.

## 3. On the receiving side, the clipboard is shared

When synced content arrives, it is written into the other device's system clipboard by default, exactly as if you had copied it there by hand. The system clipboard is a shared resource:

- On a PC, any running program can read the clipboard.
- On a phone, the app in the foreground and the keyboard can read the clipboard.
- A web page receives the content when you paste into it; some browsers also let a page read the clipboard on its own after you grant permission.

In other words, once a password syncs from your phone to your PC, any program on the PC has a chance to read it until something else overwrites the clipboard.

What you can do:

- Turn on private mode or pause sync before copying something sensitive (see section 4).
- After pasting sensitive content on the other device, copy some unrelated text to overwrite the clipboard.
- Turn off auto-apply (Windows: Preferences → Sync → Auto-apply remote content / Auto-apply remote images; Android: Preferences → Sync → Auto-write to clipboard / Auto-write remote images). Received content then goes into history only, and you copy it by hand when you need it.

## 4. Sensitive content syncs too

ClipSync does not judge whether a piece of text is a password, a card number, or a verification code. Content copied from a password manager or a banking app enters history and syncs like anything else. Three tools are built in:

| Tool | Where | Effect |
|---|---|---|
| Private mode | Preferences on both platforms; also in the Windows tray flyout | Content copied while it is on is not recorded and not synced, and is not sent later when you turn it off |
| Pause | Windows "Pause capture"; Android "Pause sync" and "Pause auto capture" | Stops capturing or stops sending; resumes according to your settings |
| Source filtering | Windows "Blocked processes": enter program names. Android "Skip sensitive content" (on by default): takes effect when the source app marks the content as sensitive, which password managers usually do | Copies from these sources are not recorded and not synced |

## 5. The pairing QR code

The QR code shown on Windows contains: this PC's address, port, certificate fingerprint, and a one-time token. The token is valid for 5 minutes, becomes invalid after one use, and is replaced after a successful pairing. The QR code does not contain the pairing secret.

A device that has this QR code can send your PC a pairing request. When the request arrives, the PC shows an approval window, both screens display the certificate fingerprint, and you have 90 seconds to compare them and click Approve. Without your approval, no pairing is created.

What you can do: watch your surroundings while the QR code is on screen and do not let anyone photograph it; reject any pairing request you did not start.

## 6. What other devices on the network can see

ClipSync announces its presence on the local network (device ID, port, certificate fingerprint) and answers any device that connects to its port with a health status (protocol version, port, device ID, and a status word saying whether clipboard writing is ready). An unpaired device on the same network learns only this much: there is a ClipSync here, on this port, with this device ID and this certificate fingerprint. It cannot read content and cannot inject content — a sync session requires the pairing secret; starting a pairing requires the token from the QR code plus your approval on the PC, and pairing requests are rate-limited.

While the pairing page is open, the phone listens for this LAN beacon from the PC (receive only, never replies; the beacon carries only the device ID, port and certificate fingerprint) to confirm "the PC is on this network" and to move the PC's real address to the front of the list — listening stops as soon as you leave the pairing page. This needs the normal `CHANGE_WIFI_MULTICAST_STATE` permission on Android (granted at install time, no prompt). While the QR window is open, the PC shortens its broadcast interval from 5 minutes to 2 seconds and returns to normal when the window closes; the broadcast content is unchanged.

## 7. What allowing the firewall means

The phone is the side that connects to the PC, so the PC has to allow inbound connections on TCP port 47654 in Windows Firewall. On first run Windows usually shows a firewall alert; tick "Private networks" and allow.

- Allow on "Private networks" only: the port is open on the home or office Wi-Fi you marked as private; on public Wi-Fi (Windows classifies new networks as "Public" by default) the port stays closed, so sync does not work there. This is the default trade-off.
- Allow on "Public networks": devices on the same segment of a hotel or café Wi-Fi can also reach the port. They still see only the information in section 6, and reading content still requires pairing — but the exposed surface is larger.
- The Windows app (Conduit page) offers a firewall status check and an allow / remove function; by default it allows private networks only. Allowing creates a firewall rule in the system; remove it in the app, or delete it by hand, before uninstalling.

Manual steps for allowing and checking are in the [install guide](install.md), section 3.

## 8. Android privileged direct read (adb / wireless debugging)

Since Android 10, ordinary apps cannot read the clipboard in the background. Privileged direct read is ClipSync's highest tier of background reading: the PC uses adb (the Android debugging tool) to send the phone one start command, which launches a helper built into ClipSync; from then on, text copied in the background — even with the screen off — uploads instantly.

What it needs:

- Developer options on the phone, with USB debugging (cable) or wireless debugging (Android 11+, same Wi-Fi) turned on.
- On the first connection, the phone shows an "Allow USB debugging?" prompt (for wireless debugging, a QR code or pairing code), which you confirm on the phone yourself. Android does not let any tool click this for you.
- A one-time adb consent checkbox in the Windows app.

What it does: when you click "Start privileged direct read", the PC runs adb once and sends the start command to the phone; at all other times the PC does not invoke adb. The helper that gets launched does one thing: read and write text on the phone's clipboard. It does not use the network and does not run other commands. It shuts down when the phone reboots, and you click once more to start it again.

Risks: USB debugging and wireless debugging are system-level developer capabilities of Android and are not limited to ClipSync. While wireless debugging is on, any device on the same network can attempt to connect to the phone's debugging port — connecting still requires you to confirm a pairing on the phone, but this is an exposed surface you should know about.

What you can do: turn wireless debugging off when you are not using it, and do not leave it on over public Wi-Fi. If you would rather not take this route, choose the overlay-polling tier (no PC needed), or just use the share sheet, quick tile, and notification copy.

## 9. Bluetooth fallback

When the local network is entirely unavailable (AP isolation, a VPN capturing all traffic, router failure), two paired devices can continue syncing over Bluetooth. It is off by default on both ends, must be turned on separately on each, and requires Bluetooth pairing in the system settings first.

- Text only. Images copied during a Bluetooth session are marked "kept on this device only" and are not sent later.
- Much slower than Wi-Fi: about 150–180 KiB/s measured on one pair of real devices.
- ClipSync's own identity check and encryption run over the Bluetooth link, reusing the pairing secret; system Bluetooth pairing is only the carrier, and after you revoke the pairing, Bluetooth cannot connect either.
- ClipSync does not make the device discoverable and connects only to the bonded device you selected. Bluetooth is still a short-range radio that devices within roughly ten meters can sense; vulnerabilities in the Bluetooth chip and the system driver are fixed by system updates.

What you can do: keep it off when you do not need it; the app switches back to Wi-Fi automatically once the network recovers.

## 10. Logs and diagnostics

The diagnostic log records only status codes, counts, and timestamps, to answer "is it connected, and where is it stuck". Clipboard content, pairing secrets, and QR tokens stay out of the log; dedicated automated tests guard this. The file exported from the tray menu "Diagnostics" can be attached to a bug report as is.

When the app crashes, the crash information stays on the device and is not uploaded anywhere. There is no telemetry and no usage statistics.

## 11. The export file is plaintext

Preferences → Data → Export history writes the whole history to one JSON Lines file. The file contains no keys, certificates, or pairing data, but every clipboard entry in it is plaintext — anyone with the file can read your whole history.

What you can do: treat the export like a sensitive file; delete it when you are done; do not casually forward it through chat apps or cloud drives.

## 12. Privacy defaults at a glance

| Item | Default | You can |
|---|---|---|
| What is processed | Text and images (PNG / JPEG) you copy | Turn off image sync |
| What is not collected | Keystrokes, touches, the screen, other apps' UI | — |
| Image sync | On | Turn off on either end |
| Received content written to clipboard | On (one switch each for text and images) | Turn off; content then goes to history only, copy by hand |
| Private mode | Off | Turn on; content is not recorded, not synced, not sent later |
| Android skip sensitive content | On | Turn off |
| Windows blocked processes | Empty | Enter program names |
| Per-item text limit | 1 MiB; larger text stays local, in full | — |
| History retention | 30 days / 2,000 entries | Adjust; clear at any time (local only) |
| Bluetooth fallback | Off | Turn on on both ends |
| Privileged direct read | Off; started only when you click on the PC | Withdraw the adb consent |
| Notifications | Say only "content received", no body | Android: turn notifications off |
| Logs | Status codes, counts, and timestamps | Export diagnostics |
| Android cloud backup | Clipboard history is excluded from system backup and device migration | Use export / import to migrate |
| Telemetry / crash reporting | None | — |

## 13. Further reading

- [Threat model](threat-model.md) — the engineering view: threats, controls, and residual risks.
- [Product scope](product-scope.md) — what ClipSync does and explicitly does not do.
- [Install and pairing guide](install.md) — firewall, proxies, Tailscale, every Android tier, and troubleshooting.
