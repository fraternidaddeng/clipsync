# ClipSync Protocol v1

Status: Stage 0 wire contract. Changes to published fields or semantics require a new protocol version and fixtures for both clients.

## 1. Scope and transport

Protocol v1 synchronizes text clipboard events directly between paired peers. It does not provide accounts, cloud relay, remote deletion, settings mutation, file transfer, or end-to-end payload encryption above TLS.

- HTTPS and WebSocket require TLS 1.3 and the saved SHA-256 certificate pin.
- WebSocket path: `/v1/peer/sync`.
- Health path: `/v1/peer/health`; it may return only protocol version, port, and device ID.
- Pairing confirmation path: `/v1/pair/confirm`.
- Per-IP connection rate limits apply before pairing work or WebSocket upgrade (see section 9 and the limits below).
- Every HTTP request sends `X-Protocol-Version: 1`. An unsupported HTTP version is rejected before WebSocket upgrade.
- WebSocket messages are UTF-8 JSON text frames. Binary frames and fragmented messages larger than the configured frame limit are rejected.
- Both directions use the same messages. Windows is the first listener implementation, not a privileged protocol authority.

The normative schemas are [envelope.schema.json](../protocol/v1/envelope.schema.json) and [messages.schema.json](../protocol/v1/messages.schema.json). JSON Schema validates shape; the semantic checks in this document are also mandatory.

## 2. JSON and envelope rules

Every message is:

```json
{
  "version": 1,
  "type": "hello",
  "request_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "body": {}
}
```

- Field names and enum values are case-sensitive ASCII.
- `version`, `type`, `request_id`, and `body` are required. Unknown envelope or body fields are rejected in v1.
- `request_id`, device IDs, and event IDs are canonical lowercase RFC 4122 UUID strings. A sender generates a fresh non-nil request ID for every message. A duplicate request ID with identical bytes is an idempotent retry; reuse with different content is `REPLAY_DETECTED`.
- Integers are JSON integers, not quoted, fractional, or exponent-form values. They must fit a signed 64-bit integer. Sequence zero is reserved for an empty continuous cursor; event sequences begin at one.
- Times are Unix epoch milliseconds in UTC. Clock values are display, expiry, and challenge inputs only; they never order events or prove consistency.
- Optional fields are omitted. They are never sent as `null`.
- Parsers reject duplicate object property names, malformed UTF-8, lone Unicode surrogates, and JSON nesting deeper than 16.
- Peers must not log a complete protocol frame, `content`, nonce, proof, pair secret, token, or private key.

## 3. Authentication and state machine

The minimum connection flow is:

```text
TLS pin + X-Protocol-Version
  -> hello
  -> challenge
  -> auth
  -> known_vector
  -> want_ranges
  -> clip_announce -> clip_fetch -> clip_payload -> ack_ranges
```

The listener sends `challenge`; connection direction does not grant trust. Before authentication, only `hello`, `challenge`, `auth`, `ping`, `pong`, and `error` are allowed. Clipboard data, vectors other than the hello snapshot, and range messages before successful authentication are rejected with `AUTH_REQUIRED` and the connection is closed.

`hello` identifies the peer, platform, client version, current local trust epoch, and an initial known-vector snapshot. The snapshot is not used or returned until authentication succeeds. Both peers send a fresh post-auth `known_vector`; that message is authoritative for the current sync calculation.

Each pairing has one independent 32-byte random pair secret, protected with DPAPI or Android Keystore. `challenge.nonce` is 32 random bytes encoded as unpadded base64url and is single-use. It expires no later than 30 seconds after issue. The responder calculates:

```text
HMAC-SHA-256(pair_secret,
  UTF8("ClipSync/v1/auth\n") ||
  UTF8(challenge_request_id) || 0x00 ||
  nonce_bytes ||
  UUID_BYTES(challenger_device_id) ||
  UUID_BYTES(responder_device_id) ||
  INT64_BE(trust_epoch))
```

`auth.proof` is the 32-byte result encoded as unpadded base64url. Verification is constant-time and binds the response to the challenge request ID, both device identities, and the current local trust epoch. A nonce or challenge request ID may be accepted once only. Expired, replayed, wrong-peer, wrong-epoch, revoked, or wrong-proof authentication closes the connection. Implementations rate-limit failed authentication attempts without logging proof values.

Revoking a peer increments the local trust epoch, removes its secret, terminates all live sessions, and prevents old sessions from acknowledging or submitting events.

## 4. Messages

### `hello`

Carries `device_id`, `platform` (`windows` or `android`), `client_version`, `trust_epoch`, and `known_vector`. A peer claiming a different device ID than the pinned pairing is rejected.

### `challenge` and `auth`

Perform pair-secret challenge-response as defined above. `auth.challenge_request_id` must equal the envelope request ID of the outstanding challenge.

### `known_vector`

For each origin, `contiguous_seq` is the greatest sequence for which every sequence from 1 through it has a persisted terminal record. `received_ranges` records persisted, non-contiguous terminal records above a gap. Origin entries are unique.

### `want_ranges`

Requests inclusive sequence ranges for one or more origins. A sender must cap and stream work rather than materializing an unbounded range.

### `clip_announce`

Sends headers before content. An `available` header includes metadata and a body hash. An `unavailable` header is an origin-authoritative terminal sequence marker with a reason. It advances synchronization without exposing content and is required when a sequence was local-only, deleted before delivery, expired, filtered for this peer, or no longer recoverable.

An unavailable marker is not a remote-delete instruction: a receiver that already owns the content keeps it according to its local history policy. A peer must persist terminal markers so a reconnect does not request the same permanent gap forever.

### `clip_fetch` and `clip_payload`

The receiver requests bodies by event ID only for announced events it does not already possess by hash. `clip_payload` repeats the complete event identity and integrity metadata. Payload without a matching announcement/fetch is `MESSAGE_OUT_OF_ORDER`.

### `ack_ranges`

Acknowledges inclusive sequence ranges only after the event or terminal marker and cursor state commit in one local transaction. It does not mean the remote event was automatically applied to the system clipboard.

### `error`

Contains only a stable `code`, retryability, and optional message type/backoff. It never contains clipboard text or arbitrary exception strings.

### `ping` and `pong`

Application messages measure liveness and use epoch milliseconds. Send `ping` every 30 seconds; after three unanswered pings, close and reconnect with the backoff in the implementation plan. WebSocket control-frame pings may be handled by the library but do not replace this interoperable application heartbeat.

## 5. Event identity and integrity

- The idempotency key is `(origin_device_id, origin_seq)`. `event_id` is a second globally unique identifier.
- The same idempotency key received again with identical identity and header is success. The same key with another event ID/hash/size/kind/time, or one event ID mapped to another origin sequence, is `EVENT_CONFLICT` and closes the connection.
- An origin sequence is allocated and committed atomically before an event enters any peer outbox. It is never reused.
- A peer may forward events from another origin only when the receiver already has a paired trust record for that origin. Protocol v1 does not treat a forwarding Windows peer as authority to invent arbitrary origin IDs. Unknown third-party origins are rejected. Origin-authenticated signatures are deferred to a later version.
- `content_hash` is lowercase hexadecimal SHA-256 over the exact strict UTF-8 bytes of `content`, with no BOM, Unicode normalization, or newline conversion. `utf8_bytes` must equal their length.
- Empty text is not a clipboard event and is not sent. One event is at most 1,048,576 UTF-8 bytes. A payload batch is also capped at 1,048,576 decoded content bytes.
- `source_app` is optional process/package metadata used by local policy. It must not be treated as authenticated evidence about the originating application.
- `expires_at_ms`, when present, must be greater than `created_at_ms`. Expiry is local retention policy; already persisted content is not remotely deleted.

## 6. Range and cursor invariants

Ranges are closed and inclusive. Within a range list they are sorted by `start_seq`, non-overlapping, non-adjacent, and normalized. Duplicate origin entries are invalid.

For `received_ranges`, every start is greater than `contiguous_seq + 1`; otherwise the sender must first normalize and advance the continuous cursor. Example:

```text
persisted: 1..10 and 12
contiguous_seq: 10
received_ranges: [{start_seq: 12, end_seq: 12}]
```

Receiving 12 does not advance the continuous cursor past 10. After 11 commits, the cursor advances through the already persisted 12 to 12 and the isolated range disappears.

An unavailable terminal marker participates in this calculation exactly like a persisted body, but it never causes deletion of an already stored body.

## 7. Limits, errors, and connection handling

- JSON depth: 16.
- Text body: 1 MiB decoded strict UTF-8; payload batch: 1 MiB decoded content.
- Announcements: 256 items; payload items: 32; fetch IDs: 128; origins per message: 128; ranges per origin: 256.
- Implementations set a 7 MiB WebSocket text-message limit because a 1 MiB JSON string can expand to six characters per code unit when escaped. Senders should emit normal UTF-8 rather than unnecessarily escaping non-ASCII text.
- No v1 payload compression is negotiated. WebSocket per-message compression is disabled for frames containing clipboard content to avoid secret-dependent compression behavior and cross-stack ambiguity.
- Rate, aggregate requested-sequence, and concurrent connection limits are implementation policy, but exceeding them returns `RATE_LIMITED` or `PAYLOAD_TOO_LARGE` without allocating unbounded memory.
- Sync WebSocket accept is limited to 30 upgrades per remote IP per minute. Exceeding that returns HTTP 429 with `{"error":"RATE_LIMITED"}` and does not upgrade the connection.
- Schema/authentication/version/event-conflict failures are fatal. Transient internal, rate, and missing-payload errors may remain connected when `retryable` is true.

An implementation may close without sending `error` when JSON cannot be parsed safely. When it sends an error, the body must use the schema enum and must not echo attacker-controlled text.

## 8. Shared fixtures and validation

`protocol/v1/fixtures/valid` contains one valid complete envelope for every v1 message. `fixtures/invalid` covers malformed JSON, strict-field/version failures, bad/overlapping cursor ranges, duplicate IDs/origins, hash and UTF-8-length mismatch, invalid expiry, empty content, and oversize declarations.

Both C# and Kotlin compatibility tests must consume these same files. They must compare parsed values, not serialized property order. In addition to schema validation, each parser needs token-level duplicate-property/depth checks and the semantic rules above.

Run the shared validator from the repository root:

```powershell
python scripts/validate-protocol.py
```

The validator requires Python 3 and `jsonschema` 4.x. Client tests remain the source of truth for System.Text.Json and kotlinx.serialization behavior.

## 9. Pairing data contract

The QR code is an out-of-band pairing bootstrap, not a sync message. It contains protocol version, HTTPS host addresses, port, listener device ID, listener display name, lowercase SHA-256 certificate fingerprint, and a one-time random token with at least 128 bits of entropy. The token expires within five minutes, is accepted once, and is invalidated on success, cancellation, or QR regeneration.

`/v1/pair/confirm` is accepted only over the exact pinned TLS certificate. Both peers display the peer name and fingerprint and require explicit confirmation before saving the 32-byte pair secret. The QR code never contains the long-term pair secret.

The frozen document shapes live in [pairing.schema.json](../protocol/v1/pairing.schema.json) with shared fixtures under `protocol/v1/fixtures/pairing`. They are intentionally not mixed into the WebSocket envelope schemas. All pairing documents follow the section 2 JSON rules: strict UTF-8, case-sensitive names, no unknown fields, no `null` for optional values, duplicate properties rejected. Every document carries a `kind` discriminator and `"version": 1`.

### QR payload (`pairing_qr`)

Rendered as one compact JSON object inside the QR code. `hosts` lists candidate IPv4/hostname strings in preference order (1..8 entries); the scanner tries each with the same `port` and pins `cert_sha256` before any request. `token` is 32 random bytes as unpadded base64url. `expires_at_ms` is display metadata; the listener's clock is authoritative for expiry.

### Confirm exchange

The scanner calls `POST /v1/pair/confirm` with `X-Protocol-Version: 1` and a `pairing_confirm_request` body: the one-time `token` plus the requester's long-term `device_id`, `display_name`, and `platform`. The request must not carry any secret material.

The listener consumes the token on first use, shows the requester's name and platform for explicit user approval, and holds the request open while waiting (bounded, 90 seconds by default). The response is:

- `200` with `pairing_confirm_response`: the listener's identity plus a freshly generated 32-byte `pair_secret` (unpadded base64url) and the `trust_epoch` under which this pairing is valid. The requester stores all of it (secret in Keystore/DPAPI) and must echo this epoch in `hello`. After re-pairing, the epoch is greater than one; the scanner must never assume `1`.
- `400` with `pairing_error` code `SCHEMA_VIOLATION`: malformed or oversized body (limit 8 KiB).
- `403` with `PAIRING_TOKEN_INVALID` (unknown, already used, or cancelled token), `PAIRING_REJECTED` (user declined), or `PAIRING_TIMEOUT` (approval window elapsed).
- `410` with `PAIRING_TOKEN_EXPIRED`.
- `429` with `PAIRING_RATE_LIMITED`: more than 10 confirm requests from the same remote IP in one minute. The listener rejects before consuming the token.

The frozen `pairing_error.error` codes are `SCHEMA_VIOLATION`, `PAIRING_TOKEN_INVALID`, `PAIRING_TOKEN_EXPIRED`, `PAIRING_REJECTED`, `PAIRING_TIMEOUT`, and `PAIRING_RATE_LIMITED`.

A confirm for an already-paired `device_id` is a re-pair: it replaces the secret, clears any revocation, and increments the trust epoch after the same explicit approval. Implementations must not log tokens or pair secrets, must compare tokens in constant time, and must zero plaintext secret buffers after protecting them.
