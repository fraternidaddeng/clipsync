# ClipSync Protocol v2

Status: Stage 9 wire contract for static clipboard images. Protocol v1 remains frozen; v2 is a new path, envelope version, and capability set. Changes to published v2 fields require a new protocol version.

## 1. Scope and transport

Protocol v2 synchronizes text clipboard events and static raster clipboard images between paired peers. It does not provide accounts, cloud relay, remote deletion, settings mutation, generic file transfer, or end-to-end payload encryption above TLS.

- HTTPS and WebSocket still require TLS 1.3 and the saved SHA-256 certificate pin.
- WebSocket path: `/v2/peer/sync`.
- Health and pairing stay on the v1 documents and `/v1/peer/health` / `/v1/pair/confirm`. Pairing QR `version` remains 1.
- Every v2 sync request sends `X-Protocol-Version: 2`. A v1-only listener rejects this header on `/v1/peer/sync` with `UNSUPPORTED_VERSION` before upgrade, as before.
- A v2-capable listener accepts `/v1/peer/sync` with `X-Protocol-Version: 1` for text-only peers, and `/v2/peer/sync` with `X-Protocol-Version: 2` for image-capable peers.
- WebSocket messages are UTF-8 JSON text frames. Binary frames are rejected in v2. Fragmented messages larger than the configured frame limit are rejected.
- A session that completed v2 hello/auth with `image_clip_v2` must not later send image bodies on a v1 connection.

The normative schemas are [envelope.schema.json](../protocol/v2/envelope.schema.json) and [messages.schema.json](../protocol/v2/messages.schema.json). JSON Schema validates shape; the semantic checks in this document are also mandatory.

## 2. Envelope

Every message is:

```json
{
  "version": 2,
  "type": "hello",
  "request_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "body": {}
}
```

v1 envelope rules still apply: case-sensitive names, no unknown fields, no `null` for optional values, canonical UUIDs, signed 64-bit integers, Unix epoch milliseconds, JSON depth 16, duplicate properties rejected. Parsers must not log a complete frame, image bytes, `content`, nonce, proof, pair secret, token, or private key.

v2 adds three payload types: `clip_payload_begin`, `clip_payload_chunk`, `clip_payload_end`. Text still uses `clip_payload`.

## 3. Authentication and capabilities

The minimum connection flow is unchanged:

```text
TLS pin + X-Protocol-Version: 2
  -> hello
  -> challenge
  -> auth
  -> known_vector
  -> want_ranges
  -> clip_announce -> clip_fetch -> (text clip_payload | image begin/chunk/end) -> ack_ranges
```

`hello.capabilities` is required. Known capability tokens are `image_clip_v2`. Unknown tokens are rejected. Duplicate tokens are rejected. At most 16 entries. Image bodies may be sent only when both peers listed `image_clip_v2`.

The responder calculates:

```text
HMAC-SHA-256(pair_secret,
  UTF8("ClipSync/v2/auth\n") ||
  UTF8(challenge_request_id) || 0x00 ||
  nonce_bytes ||
  UUID_BYTES(challenger_device_id) ||
  UUID_BYTES(responder_device_id) ||
  INT64_BE(trust_epoch) || 0x00 ||
  INT64_BE(2))
```

v1 connections keep the v1 prefix and do not append the protocol version. A v2 proof is not valid on a v1 session and vice versa.

## 4. Image headers

An `available` text header is the v1 header (`kind=text`, `utf8_bytes`, `content_hash` of UTF-8 bytes).

An `available` image header requires:

- `kind` = `image`
- `mime_type` = `image/png` or `image/jpeg`
- `content_hash` = lowercase hex SHA-256 of the exact encoded bytes that will be stored and transmitted
- `encoded_bytes` = 1..16,777,216
- `pixel_width`, `pixel_height` = 1..8192
- `pixel_width * pixel_height` ≤ 33,554,432 (32 MP)
- `event_id`, `origin_device_id`, `origin_seq`, `availability`, `created_at_ms`
- optional `source_app`, `expires_at_ms`

GIF, SVG, HTML, video, unknown MIME, file URIs, and mixed extra fields (`utf8_bytes` on an image header, or image fields on a text header) are schema/semantic violations.

An `unavailable` header in v2 may use `unsupported_media` in addition to the v1 reasons. It is an origin-authoritative terminal marker. A receiver that already owns the image keeps it. The sender talking to a v1 peer must not emit `unsupported_media` on the wire; it sends `local_only` and records `unsupported_media` locally.

## 5. Image payload transfer

After `clip_announce` / `clip_fetch`, an image body is:

```text
clip_payload_begin -> clip_payload_chunk* -> clip_payload_end
```

- `transfer_id` is a fresh canonical UUID. It cannot be reused across events.
- `clip_payload_begin` binds `transfer_id`, `event_id`, `chunk_count` (≥1), `encoded_bytes`, `content_hash`, and `mime_type`.
- Each `clip_payload_chunk` binds the same `transfer_id` and `event_id`, plus `chunk_index` (0-based), `chunk_count`, `chunk_bytes`, and unpadded base64url `data`.
- Decoded `data` length must equal `chunk_bytes` and must be 1..262,144. The last chunk may be shorter.
- Sum of `chunk_bytes` must equal `encoded_bytes` from begin/header and must not exceed 16 MiB.
- Chunks may arrive in order only for the MVP state machine; out-of-order or duplicate index with different bytes is `MEDIA_OUT_OF_ORDER`. An identical retry of the same index is idempotent.
- `clip_payload_end` repeats `transfer_id`, `event_id`, and `content_hash`.
- Payload before a matching announcement/fetch is `MESSAGE_OUT_OF_ORDER`.
- The receiver writes chunks to a temporary file, verifies MIME magic, declared dimensions, encoded size, and hash, then commits the blob reference and clip row in one transaction before `ack_ranges`.

Identical `content_hash` already stored as a live blob may skip the byte transfer, but the new `(origin_device_id, origin_seq)` still commits its own event row.

## 6. Limits and errors

| Limit | Value |
|---|---|
| Encoded image | 16 MiB |
| Decoded pixels | 32 MP |
| Max side | 8192 px |
| Chunk decoded size | 256 KiB |
| Concurrent image downloads | 2 |
| Unfinished download retention | 24 h |
| Thumbnail max side | 512 px |
| Image payload batch | 16 MiB encoded |
| WebSocket text frame | 7 MiB (unchanged; chunks stay well below this) |

New error codes:

| Code | Retryable | Meaning |
|---|---|---|
| `UNSUPPORTED_MEDIA` | false | Peer or local policy does not accept this image |
| `MEDIA_TOO_LARGE` | false | Encoded size, pixels, or side exceeded |
| `MEDIA_DECODE_FAILED` | false | Magic, dimensions, or bounded decode failed |
| `MEDIA_HASH_MISMATCH` | false | Bytes do not match `content_hash` |
| `MEDIA_OUT_OF_ORDER` | false | Chunk index/count/transfer binding is illegal |
| `MEDIA_STORAGE_FAILED` | true | Temporary disk/commit failure without unbounded allocation |

Protocol/auth/event-conflict failures remain fatal. Image transfer must not block text sync: image downloads are separately limited.

## 7. Mixed-version peers

- v1 fixtures and parsers continue to reject `version: 2` and unknown message types.
- A v2 sender announcing an image to a v1 session emits a v1-legal unavailable header (`local_only`) so the continuous cursor advances.
- Empty text, data URIs, and unbounded want loops are forbidden in both versions.

## 8. Shared fixtures

`protocol/v2/fixtures/valid` and `protocol/v2/fixtures/invalid` are the cross-language contract. C#, Kotlin, and `scripts/validate-protocol.py` consume the same files. Binary PNG/JPEG samples used by storage tests live beside the JSON fixtures and are not logged.
