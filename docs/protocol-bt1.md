# ClipSync Secure Channel bt1

Status: Stage bt-1 wire contract (ADR 0005, implementation plan phase 1). bt1 is a transport-level secure channel, not a new sync protocol version: an unmodified protocol v1 session runs inside it. Changes to published bt1 fields or cryptographic layouts require a new channel version (`bt2`) and fixtures for both clients.

## 1. Scope and role

bt1 replaces the "TLS 1.3 + certificate pin" layer when the peers talk over a raw reliable byte stream without TLS — in ADR 0005 that stream is a Bluetooth Classic RFCOMM socket, but bt1 itself is stream-agnostic and has no Bluetooth dependency. It provides:

- mutual authentication bound to the existing 32-byte pair secret, both device identities, and the current `trust_epoch` (revocation semantics identical to the IP path);
- per-direction AES-256-GCM session keys derived per connection;
- a length-prefixed AEAD frame layer with strict anti-replay and anti-reorder guarantees.

It intentionally does not provide: pairing or any second trust root (the pair secret must already exist from the QR/IP pairing flow), forward secrecy (the threat model equals the existing "pair secret compromise compromises the session" bar of protocol v1 auth), rekeying, compression, or multiplexing.

Roles are fixed by who dials the underlying stream:

- **client** — the side that opened the connection (Android in the RFCOMM deployment).
- **listener** — the side that accepted it (Windows in the RFCOMM deployment).

After the handshake completes, every protocol v1 message that would have been one WebSocket text frame is carried as exactly one encrypted bt1 frame, in both directions. Session flow, message semantics, limits, cursors, idempotency, and error codes of `docs/protocol-v1.md` apply unchanged inside the channel. Per ADR 0005 §4, a session over Bluetooth must not declare the `image_clip_v2` capability in its inner v1 `hello`.

## 2. Frame layer

All bytes on the stream, from the first byte of the handshake onward, belong to frames:

```text
frame = UINT32_BE(payload_length) || payload
```

- `payload_length` is the exact byte length of `payload`, big-endian, unsigned.
- During the handshake (§3) the payload is plaintext strict UTF-8 JSON. A handshake payload is at least 2 and at most 4096 bytes; a longer or empty declared length before handshake completion is `BT1_SCHEMA_VIOLATION` (or `BT1_FRAME_TOO_LARGE` above the cap).
- After the handshake the payload is AES-256-GCM ciphertext (§5). Plaintext per frame is at least 1 and at most 7,340,032 bytes (7 MiB, matching the v1 WebSocket text-message limit), so `payload_length` is at least 17 and at most 7,340,048 bytes (plaintext plus the 16-byte tag). A declared length outside that window closes the connection without attempting decryption.
- Receivers must enforce the declared-length caps before allocating or buffering the payload.

## 3. Handshake

The handshake is four messages, strictly in this order, each in its own plaintext frame:

```text
client   -> listener : bt1_client_hello
listener -> client   : bt1_listener_hello
client   -> listener : bt1_client_auth
listener -> client   : bt1_listener_auth
```

No business byte crosses the stream in either direction until both proofs verify. The client proves first; a listener that received an invalid `bt1_client_auth` must not send its own proof. Either side may substitute `bt1_error` for its next message and close (§6). An implementation must abort the handshake if it has not completed within 30 seconds.

Handshake JSON obeys the protocol v1 section 2 rules: case-sensitive ASCII names, required fields only plus no unknown fields, no `null`, canonical lowercase UUID strings, signed 64-bit integers, duplicate properties rejected, strict UTF-8. Every message carries a `kind` discriminator and `"version": 1` (the bt1 channel version). The normative schema is [handshake.schema.json](../protocol/bt1/handshake.schema.json).

### `bt1_client_hello` and `bt1_listener_hello`

```json
{
  "kind": "bt1_client_hello",
  "version": 1,
  "device_id": "11111111-1111-4111-8111-111111111111",
  "trust_epoch": 1,
  "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
}
```

- `device_id` is the sender's long-term device UUID.
- `trust_epoch` is the sender's current local trust epoch for this pairing (≥ 1).
- `nonce` is 32 fresh random bytes as unpadded base64url, single-use, generated for every handshake attempt. `nonce_c` denotes the client's nonce, `nonce_l` the listener's.

Validation before answering: the listener accepts a `bt1_client_hello` only when `device_id` matches a stored pairing with a pair secret; the client accepts a `bt1_listener_hello` only when `device_id` equals the paired listener it dialed. A peer whose advertised `trust_epoch` differs from the local trust epoch for that pairing is rejected with `BT1_AUTH_FAILED` (the advertised value is diagnostic only; the proof binds the epoch cryptographically). A peer claiming the local device's own ID is rejected.

### `bt1_client_auth` and `bt1_listener_auth`

```json
{
  "kind": "bt1_client_auth",
  "version": 1,
  "proof": "…43 chars of unpadded base64url…"
}
```

`proof` is the 32-byte result of:

```text
HMAC-SHA-256(pair_secret,
  UTF8("ClipSync/bt1/auth\n") ||
  UTF8(role) || 0x00 ||
  nonce_c || nonce_l ||
  UUID_BYTES(client_device_id) ||
  UUID_BYTES(listener_device_id) ||
  INT64_BE(trust_epoch))
```

- `role` is the sender's role string, exactly `client` or `listener`; it makes the two directions' proofs distinct and prevents reflection.
- `nonce_c` and `nonce_l` are the raw 32-byte nonces from the two hellos, always client's first regardless of who is proving.
- `UUID_BYTES` is RFC 4122 big-endian byte order, identical to protocol v1.
- `trust_epoch` is the verifier's local trust epoch; both sides must hold the same epoch or verification fails.
- The domain-separation prefix `ClipSync/bt1/auth\n` guarantees a bt1 proof can never be replayed as a v1/v2 challenge proof or vice versa.

Verification is constant-time. Expired, replayed, wrong-peer, wrong-epoch, revoked, wrong-role, or wrong-proof authentication closes the connection with `BT1_AUTH_FAILED`. Listeners rate-limit failed attempts (`BT1_RATE_LIMITED`) without logging nonce or proof values; the MAC address of the underlying link is routing metadata only and never participates in trust decisions.

## 4. Key derivation

After both proofs verify, both sides derive the session keys with HKDF-SHA-256 (RFC 5869):

```text
okm = HKDF-SHA-256(
  ikm  = pair_secret,
  salt = nonce_c || nonce_l,
  info = UTF8("ClipSync/bt1/keys"),
  length = 64)

key_client_to_listener = okm[0..31]
key_listener_to_client = okm[32..63]
```

Each direction has an independent AES-256 key. Keys live only in memory, are never persisted or logged, and are discarded when the connection closes. Because both nonces are fresh, keys never repeat across connections even though the ikm is the long-term pair secret.

## 5. Encrypted frames

Post-handshake payloads are AES-256-GCM with a 16-byte tag and no additional authenticated data:

```text
nonce      = 0x00 0x00 0x00 0x00 || UINT64_BE(sequence)   (12 bytes)
ciphertext = AES-256-GCM-Encrypt(key_direction, nonce, plaintext)
payload    = ciphertext || tag                              (as produced by GCM)
```

- `sequence` starts at 0 for the first encrypted frame in each direction and increments by exactly 1 per frame, independently per direction.
- The sequence is never transmitted. The receiver decrypts with its own expected counter, so any replayed, reordered, dropped, truncated, or tampered frame fails tag verification. GCM failure is fatal: close the connection immediately (`BT1_DECRYPT_FAILED` is a log/diagnostic code, not a wire message — see §6).
- A sender must close the session before its counter would overflow; with a 64-bit counter this bound is unreachable in practice, but implementations must still check rather than wrap.
- Plaintext is exactly one protocol v1 UTF-8 JSON text message (1 byte to 7 MiB). Zero-length plaintext is invalid. Inner v1 parsing errors are handled by protocol v1 rules (its own `error` message inside the channel).
- No compression is applied at the bt1 layer, matching the v1 decision to avoid secret-dependent compression behavior.

## 6. Errors

During the handshake, either side may send one plaintext `bt1_error` frame and close:

```json
{
  "kind": "bt1_error",
  "version": 1,
  "code": "BT1_AUTH_FAILED"
}
```

| Code | Meaning | Sent on wire |
|---|---|---|
| `BT1_SCHEMA_VIOLATION` | Malformed handshake JSON, unknown `kind`, unknown/missing field, wrong field shape, out-of-order message, or an invalid handshake frame length | yes (handshake only) |
| `BT1_VERSION_UNSUPPORTED` | `version` is not a supported bt1 channel version | yes (handshake only) |
| `BT1_AUTH_FAILED` | Unknown/wrong device, trust-epoch mismatch, revoked pairing, or proof verification failure | yes (handshake only) |
| `BT1_RATE_LIMITED` | Listener throttling after repeated failed handshakes | yes (handshake only) |
| `BT1_FRAME_TOO_LARGE` | Declared frame length above the applicable cap | handshake: yes; after handshake: close only |
| `BT1_DECRYPT_FAILED` | AEAD tag verification failed after the handshake | never (close only, local diagnostics) |

The error body never echoes attacker-controlled text and never contains nonces, proofs, secrets, or keys. After the handshake has completed, no plaintext frame is ever valid in either direction; a post-handshake failure closes the stream without a wire message. An implementation may also close without `bt1_error` when the peer's bytes cannot be parsed safely.

Like protocol v1, peers must not log a complete frame, plaintext, nonce, proof, pair secret, or derived key.

## 7. Shared fixtures and validation

Cross-language vectors live under `protocol/bt1/fixtures/`:

- `handshake/vectors.json` — given pair secret, both device IDs, trust epoch, and both nonces: the expected client proof, listener proof, and both derived direction keys. Pins §3–§4 bit-for-bit.
- `frames/vectors.json` — given a direction key, sequence number, and plaintext: the expected complete frame bytes (length prefix included). Pins §2 and §5 bit-for-bit, including the counter-nonce layout.
- `handshake/valid/` and `handshake/invalid/` — handshake message documents that must parse (or be rejected) identically by both clients under [handshake.schema.json](../protocol/bt1/handshake.schema.json) plus the semantic rules above.

`scripts/generate-bt1-vectors.py` is the frozen, independent reference implementation that produced the vectors; rerunning it must be a no-op unless the channel version changes. `scripts/validate-protocol.py` recomputes every proof, key, and frame and validates the message fixtures; it requires Python 3 with `jsonschema` 4.x and `cryptography`. Client unit tests on both platforms must consume the same files: C# in `ClipSync.Tests` (`Security/Bt1*`), Kotlin in `com.clipsync.android.sync` (`Bt1*Test`). Tamper, replay, out-of-order, truncation, and oversize behavior is covered by negative tests on both sides.
