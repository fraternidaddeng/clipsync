#!/usr/bin/env python3
"""Generate protocol/v2 fixtures, media samples, and auth vectors."""

from __future__ import annotations

import base64
import hashlib
import hmac
import io
import json
import struct
import zlib
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "protocol" / "v2" / "fixtures"
VALID = FIXTURES / "valid"
INVALID = FIXTURES / "invalid"
AUTH = FIXTURES / "auth"
MEDIA = FIXTURES / "media"

DEV1 = "11111111-1111-4111-8111-111111111111"
DEV2 = "22222222-2222-4222-8222-222222222222"
EV_TEXT = "33333333-3333-4333-8333-333333333333"
EV_UNAV = "44444444-4444-4444-8444-444444444444"
EV_IMG = "55555555-5555-4555-8555-555555555555"
TR = "66666666-6666-4666-8666-666666666666"
RID = {
    "hello": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "challenge": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "auth": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "known_vector": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    "want_ranges": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "clip_announce": "ffffffff-ffff-4fff-8fff-ffffffffffff",
    "clip_fetch": "12121212-1212-4121-8121-121212121212",
    "clip_payload": "13131313-1313-4131-8131-131313131313",
    "ack_ranges": "14141414-1414-4141-8141-141414141414",
    "error": "15151515-1515-4151-8151-151515151515",
    "ping": "16161616-1616-4161-8161-161616161616",
    "pong": "17171717-1717-4171-8171-171717171717",
    "begin": "18181818-1818-4181-8181-181818181818",
    "chunk": "19191919-1919-4191-8191-191919191919",
    "end": "1a1a1a1a-1a1a-41a1-81a1-1a1a1a1a1a1a",
}


def png_chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def make_png(width: int, height: int, rgba: bytes) -> bytes:
    raw = b""
    stride = width * 4
    for y in range(height):
        raw += b"\x00" + rgba[y * stride : (y + 1) * stride]
    compressed = zlib.compress(raw, 9)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", compressed)
        + png_chunk(b"IEND", b"")
    )


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def dump(path: Path, obj: object) -> None:
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def env(typ: str, body: dict, rid: str | None = None) -> dict:
    return {"version": 2, "type": typ, "request_id": rid or RID[typ], "body": body}


def uuid_bytes(value: str) -> bytes:
    return bytes.fromhex(value.replace("-", ""))


def v2_proof(
    secret: bytes,
    request_id: str,
    nonce: bytes,
    challenger: str,
    responder: str,
    epoch: int,
) -> bytes:
    prefix = b"ClipSync/v2/auth\n"
    request_bytes = request_id.encode("utf-8")
    message = (
        prefix
        + request_bytes
        + b"\x00"
        + nonce
        + uuid_bytes(challenger)
        + uuid_bytes(responder)
        + struct.pack(">q", epoch)
        + b"\x00"
        + struct.pack(">q", 2)
    )
    return hmac.new(secret, message, hashlib.sha256).digest()


def main() -> None:
    for path in (VALID, INVALID, AUTH, MEDIA):
        path.mkdir(parents=True, exist_ok=True)

    png1 = make_png(1, 1, bytes([0, 0, 0, 0]))
    rgba8 = bytearray()
    for y in range(8):
        for x in range(8):
            rgba8 += bytes([0, 255, 0, 128] if (x + y) % 2 == 0 else [0, 0, 255, 255])
    png8 = make_png(8, 8, bytes(rgba8))
    (MEDIA / "png-1x1-transparent.png").write_bytes(png1)
    (MEDIA / "png-8x8.png").write_bytes(png8)

    image = Image.new("RGB", (1, 1), (255, 0, 0))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=80)
    jpeg = buffer.getvalue()
    (MEDIA / "jpeg-1x1.jpg").write_bytes(jpeg)

    mid = len(png8) // 2

    dump(
        VALID / "hello.json",
        env(
            "hello",
            {
                "device_id": DEV1,
                "platform": "windows",
                "client_version": "0.2.0",
                "trust_epoch": 1,
                "capabilities": ["image_clip_v2"],
                "known_vector": {
                    "origins": [
                        {
                            "origin_device_id": DEV1,
                            "contiguous_seq": 3,
                            "received_ranges": [{"start_seq": 5, "end_seq": 6}],
                        }
                    ]
                },
            },
        ),
    )
    dump(
        VALID / "challenge.json",
        env(
            "challenge",
            {
                "algorithm": "hmac-sha256",
                "nonce": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                "challenger_device_id": DEV1,
                "responder_device_id": DEV2,
                "trust_epoch": 1,
                "expires_at_ms": 1776000000000,
            },
        ),
    )
    dump(
        VALID / "auth.json",
        env(
            "auth",
            {
                "algorithm": "hmac-sha256",
                "challenge_request_id": RID["challenge"],
                "responder_device_id": DEV2,
                "trust_epoch": 1,
                "proof": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
            },
        ),
    )
    dump(
        VALID / "known_vector.json",
        env(
            "known_vector",
            {
                "origins": [
                    {"origin_device_id": DEV1, "contiguous_seq": 10},
                    {
                        "origin_device_id": DEV2,
                        "contiguous_seq": 4,
                        "received_ranges": [{"start_seq": 7, "end_seq": 8}],
                    },
                ]
            },
        ),
    )
    dump(
        VALID / "want_ranges.json",
        env(
            "want_ranges",
            {
                "requests": [
                    {
                        "origin_device_id": DEV1,
                        "ranges": [
                            {"start_seq": 4, "end_seq": 4},
                            {"start_seq": 7, "end_seq": 9},
                        ],
                    }
                ]
            },
        ),
    )
    dump(
        VALID / "clip_announce.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_TEXT,
                        "origin_device_id": DEV1,
                        "origin_seq": 4,
                        "availability": "available",
                        "kind": "text",
                        "content_hash": "7ee46a9cda0560475782f6d67f83924d1aa6e5d1565e074d1c1b499fb48cdbd1",
                        "utf8_bytes": 22,
                        "source_app": "notepad.exe",
                        "created_at_ms": 1776000000000,
                        "expires_at_ms": 1778592000000,
                    },
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 6,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "source_app": "photos.exe",
                        "created_at_ms": 1776000000000,
                    },
                    {
                        "event_id": EV_UNAV,
                        "origin_device_id": DEV2,
                        "origin_seq": 5,
                        "availability": "unavailable",
                        "reason": "unsupported_media",
                    },
                ]
            },
        ),
    )
    dump(VALID / "clip_fetch.json", env("clip_fetch", {"event_ids": [EV_IMG]}))
    dump(
        VALID / "clip_payload.json",
        env(
            "clip_payload",
            {
                "clips": [
                    {
                        "event_id": EV_TEXT,
                        "origin_device_id": DEV1,
                        "origin_seq": 4,
                        "kind": "text",
                        "content": "你好，ClipSync 👋",
                        "content_hash": "7ee46a9cda0560475782f6d67f83924d1aa6e5d1565e074d1c1b499fb48cdbd1",
                        "utf8_bytes": 22,
                        "source_app": "notepad.exe",
                        "created_at_ms": 1776000000000,
                        "expires_at_ms": 1778592000000,
                    }
                ]
            },
        ),
    )
    dump(
        VALID / "clip_payload_begin.json",
        env(
            "clip_payload_begin",
            {
                "transfer_id": TR,
                "event_id": EV_IMG,
                "chunk_count": 2,
                "encoded_bytes": len(png8),
                "content_hash": sha(png8),
                "mime_type": "image/png",
            },
            RID["begin"],
        ),
    )
    dump(
        VALID / "clip_payload_chunk.json",
        env(
            "clip_payload_chunk",
            {
                "transfer_id": TR,
                "event_id": EV_IMG,
                "chunk_index": 0,
                "chunk_count": 2,
                "chunk_bytes": mid,
                "data": b64url(png8[:mid]),
            },
            RID["chunk"],
        ),
    )
    dump(
        VALID / "clip_payload_end.json",
        env(
            "clip_payload_end",
            {"transfer_id": TR, "event_id": EV_IMG, "content_hash": sha(png8)},
            RID["end"],
        ),
    )
    dump(
        VALID / "ack_ranges.json",
        env(
            "ack_ranges",
            {"acks": [{"origin_device_id": DEV1, "ranges": [{"start_seq": 1, "end_seq": 4}]}]},
        ),
    )
    dump(
        VALID / "error.json",
        env(
            "error",
            {"code": "MEDIA_TOO_LARGE", "retryable": False, "failed_type": "clip_payload_begin"},
        ),
    )
    dump(VALID / "ping.json", env("ping", {"sent_at_ms": 1776000000000}))
    dump(
        VALID / "pong.json",
        env("pong", {"ping_sent_at_ms": 1776000000000, "sent_at_ms": 1776000000010}),
    )

    dump(
        INVALID / "unsupported_version.json",
        {"version": 1, "type": "ping", "request_id": RID["ping"], "body": {"sent_at_ms": 1776000000000}},
    )
    dump(
        INVALID / "unknown_envelope_field.json",
        {
            "version": 2,
            "type": "ping",
            "request_id": RID["ping"],
            "body": {"sent_at_ms": 1776000000000},
            "extra": True,
        },
    )
    dump(
        INVALID / "unknown_capability.json",
        env(
            "hello",
            {
                "device_id": DEV1,
                "platform": "windows",
                "client_version": "0.2.0",
                "trust_epoch": 1,
                "capabilities": ["image_clip_v2", "file_transfer_v1"],
                "known_vector": {"origins": []},
            },
        ),
    )
    dump(
        INVALID / "hello_missing_capabilities.json",
        {
            "version": 2,
            "type": "hello",
            "request_id": RID["hello"],
            "body": {
                "device_id": DEV1,
                "platform": "windows",
                "client_version": "0.2.0",
                "trust_epoch": 1,
                "known_vector": {"origins": []},
            },
        },
    )
    dump(
        INVALID / "gif_mime.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/gif",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "image_header_with_utf8_bytes.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "utf8_bytes": 10,
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "encoded_bytes_too_large.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": 16777217,
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "pixel_side_too_large.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 8193,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "pixel_bomb.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 8192,
                        "pixel_height": 8192,
                        "created_at_ms": 1776000000000,
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "chunk_too_large.json",
        env(
            "clip_payload_chunk",
            {
                "transfer_id": TR,
                "event_id": EV_IMG,
                "chunk_index": 0,
                "chunk_count": 1,
                "chunk_bytes": 262145,
                "data": "AA",
            },
            RID["chunk"],
        ),
    )
    dump(
        INVALID / "chunk_padded_base64.json",
        env(
            "clip_payload_chunk",
            {
                "transfer_id": TR,
                "event_id": EV_IMG,
                "chunk_index": 0,
                "chunk_count": 1,
                "chunk_bytes": len(png1),
                "data": base64.urlsafe_b64encode(png1).decode("ascii"),
            },
            RID["chunk"],
        ),
    )
    dump(
        INVALID / "chunk_bytes_mismatch.json",
        env(
            "clip_payload_chunk",
            {
                "transfer_id": TR,
                "event_id": EV_IMG,
                "chunk_index": 0,
                "chunk_count": 1,
                "chunk_bytes": len(png1) + 1,
                "data": b64url(png1),
            },
            RID["chunk"],
        ),
    )
    (INVALID / "malformed.json").write_text("{\n", encoding="utf-8")
    dump(
        INVALID / "unknown_unavailable_reason.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_UNAV,
                        "origin_device_id": DEV2,
                        "origin_seq": 5,
                        "availability": "unavailable",
                        "reason": "file_transfer",
                    }
                ]
            },
        ),
    )
    dump(
        INVALID / "duplicate_event_id.json",
        env(
            "clip_announce",
            {
                "clips": [
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 1,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    },
                    {
                        "event_id": EV_IMG,
                        "origin_device_id": DEV1,
                        "origin_seq": 2,
                        "availability": "available",
                        "kind": "image",
                        "mime_type": "image/png",
                        "content_hash": sha(png1),
                        "encoded_bytes": len(png1),
                        "pixel_width": 1,
                        "pixel_height": 1,
                        "created_at_ms": 1776000000000,
                    },
                ]
            },
        ),
    )

    vectors = []
    secret = bytes(range(32))
    nonce = bytes(32)
    vectors.append(
        {
            "name": "sequential secret, zero nonce, epoch 1, protocol 2",
            "pair_secret_hex": secret.hex(),
            "challenge_request_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "nonce_base64url": b64url(nonce),
            "challenger_device_id": DEV1,
            "responder_device_id": DEV2,
            "trust_epoch": 1,
            "protocol_version": 2,
            "proof_base64url": b64url(
                v2_proof(secret, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", nonce, DEV1, DEV2, 1)
            ),
        }
    )
    secret = bytes([0xFF] * 32)
    nonce = bytes(range(32))
    vectors.append(
        {
            "name": "all-ones secret, sequential nonce, max epoch, protocol 2",
            "pair_secret_hex": secret.hex(),
            "challenge_request_id": "0f0e0d0c-0b0a-4908-8706-050403020100",
            "nonce_base64url": b64url(nonce),
            "challenger_device_id": "fedcba98-7654-4321-8765-432187654321",
            "responder_device_id": "01234567-89ab-4def-8123-456789abcdef",
            "trust_epoch": 9223372036854775807,
            "protocol_version": 2,
            "proof_base64url": b64url(
                v2_proof(
                    secret,
                    "0f0e0d0c-0b0a-4908-8706-050403020100",
                    nonce,
                    "fedcba98-7654-4321-8765-432187654321",
                    "01234567-89ab-4def-8123-456789abcdef",
                    9223372036854775807,
                )
            ),
        }
    )
    secret = bytes.fromhex("86aec21e3225dc47f39800c0cc163ff8c429f45bc8a8926a820470b30a3d2cf9")
    nonce = base64.urlsafe_b64decode("4K8GEZcBd2Z4HOblxP_AcZ3D4JTMsyWgI4LVoaWbv_8=")
    vectors.append(
        {
            "name": "mixed secret, epoch 42, protocol 2",
            "pair_secret_hex": secret.hex(),
            "challenge_request_id": "c0ffee00-1234-4abc-9def-000000000042",
            "nonce_base64url": b64url(nonce),
            "challenger_device_id": "aaaabbbb-cccc-4ddd-8eee-ffff00001111",
            "responder_device_id": "99998888-7777-4666-b555-444433332222",
            "trust_epoch": 42,
            "protocol_version": 2,
            "proof_base64url": b64url(
                v2_proof(
                    secret,
                    "c0ffee00-1234-4abc-9def-000000000042",
                    nonce,
                    "aaaabbbb-cccc-4ddd-8eee-ffff00001111",
                    "99998888-7777-4666-b555-444433332222",
                    42,
                )
            ),
        }
    )
    dump(
        AUTH / "vectors.json",
        {
            "algorithm": "hmac-sha256",
            "message_format": (
                "UTF8('ClipSync/v2/auth\\n') || UTF8(challenge_request_id) || 0x00 || "
                "nonce_bytes || UUID_BYTES(challenger_device_id) || UUID_BYTES(responder_device_id) || "
                "INT64_BE(trust_epoch) || 0x00 || INT64_BE(2)"
            ),
            "vectors": vectors,
        },
    )

    manifest = {
        "png_1x1_sha256": sha(png1),
        "png_1x1_bytes": len(png1),
        "png_8x8_sha256": sha(png8),
        "png_8x8_bytes": len(png8),
        "jpeg_1x1_sha256": sha(jpeg),
        "jpeg_1x1_bytes": len(jpeg),
        "png_8x8_chunk0_bytes": mid,
        "png_8x8_chunk1_bytes": len(png8) - mid,
    }
    dump(MEDIA / "manifest.json", manifest)
    print(f"valid={len(list(VALID.glob('*.json')))} invalid={len(list(INVALID.glob('*.json')))}")
    print(json.dumps(manifest))


if __name__ == "__main__":
    main()
