#!/usr/bin/env python3
"""Generate bt1 secure-channel test vectors (independent reference implementation).

The output files under protocol/bt1/fixtures/ are frozen; rerunning this script
must be a no-op unless the channel version changes. Both clients must reproduce
these proofs, keys, and frames bit-for-bit. See docs/protocol-bt1.md sections
3-5 for the layouts. Requires the `cryptography` package for AES-256-GCM.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import struct
import uuid
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
HANDSHAKE_OUTPUT = ROOT / "protocol" / "bt1" / "fixtures" / "handshake" / "vectors.json"
FRAMES_OUTPUT = ROOT / "protocol" / "bt1" / "fixtures" / "frames" / "vectors.json"

AUTH_PREFIX = b"ClipSync/bt1/auth\n"
KEYS_INFO = b"ClipSync/bt1/keys"

PROOF_MESSAGE_FORMAT = (
    "UTF8('ClipSync/bt1/auth\\n') || UTF8(role) || 0x00 || nonce_client || nonce_listener || "
    "UUID_BYTES(client_device_id) || UUID_BYTES(listener_device_id) || INT64_BE(trust_epoch)"
)
KEY_DERIVATION_FORMAT = (
    "HKDF-SHA-256(ikm=pair_secret, salt=nonce_client||nonce_listener, "
    "info=UTF8('ClipSync/bt1/keys'), length=64); "
    "key_client_to_listener=okm[0..31], key_listener_to_client=okm[32..63]"
)
FRAME_FORMAT = (
    "UINT32_BE(len(ciphertext)) || ciphertext; ciphertext = AES-256-GCM(key, nonce, plaintext) "
    "with the 16-byte tag appended; nonce = 0x00000000 || UINT64_BE(sequence)"
)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def compute_proof(
    pair_secret: bytes,
    role: str,
    nonce_client: bytes,
    nonce_listener: bytes,
    client_device_id: str,
    listener_device_id: str,
    trust_epoch: int,
) -> bytes:
    message = (
        AUTH_PREFIX
        + role.encode("utf-8")
        + b"\x00"
        + nonce_client
        + nonce_listener
        + uuid.UUID(client_device_id).bytes
        + uuid.UUID(listener_device_id).bytes
        + struct.pack(">q", trust_epoch)
    )
    return hmac.new(pair_secret, message, hashlib.sha256).digest()


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def derive_keys(pair_secret: bytes, nonce_client: bytes, nonce_listener: bytes) -> tuple[bytes, bytes]:
    okm = hkdf_sha256(pair_secret, nonce_client + nonce_listener, KEYS_INFO, 64)
    return okm[:32], okm[32:]


def encrypt_frame(key: bytes, sequence: int, plaintext: bytes) -> bytes:
    nonce = b"\x00" * 4 + struct.pack(">Q", sequence)
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, None)
    return struct.pack(">I", len(ciphertext)) + ciphertext


def generate_handshake_vectors() -> list[dict[str, object]]:
    inputs = [
        {
            "name": "sequential secret, zero client nonce, epoch 1",
            "pair_secret": bytes(range(32)),
            "client_device_id": "11111111-1111-4111-8111-111111111111",
            "listener_device_id": "22222222-2222-4222-8222-222222222222",
            "trust_epoch": 1,
            "nonce_client": bytes(32),
            "nonce_listener": bytes(range(32)),
        },
        {
            "name": "all-ones secret, descending listener nonce, max epoch",
            "pair_secret": b"\xff" * 32,
            "client_device_id": "fedcba98-7654-4321-8765-432187654321",
            "listener_device_id": "01234567-89ab-4def-8123-456789abcdef",
            "trust_epoch": 9223372036854775807,
            "nonce_client": bytes(range(32)),
            "nonce_listener": bytes(range(255, 223, -1)),
        },
        {
            "name": "mixed secret, hashed nonces, epoch 42",
            "pair_secret": hashlib.sha256(b"clipsync-bt1-vector-3").digest(),
            "client_device_id": "aaaabbbb-cccc-4ddd-8eee-ffff00001111",
            "listener_device_id": "99998888-7777-4666-b555-444433332222",
            "trust_epoch": 42,
            "nonce_client": hashlib.sha256(b"clipsync-bt1-nonce-c3").digest(),
            "nonce_listener": hashlib.sha256(b"clipsync-bt1-nonce-l3").digest(),
        },
    ]

    vectors = []
    for item in inputs:
        key_c2l, key_l2c = derive_keys(item["pair_secret"], item["nonce_client"], item["nonce_listener"])
        vectors.append(
            {
                "name": item["name"],
                "pair_secret_hex": item["pair_secret"].hex(),
                "client_device_id": item["client_device_id"],
                "listener_device_id": item["listener_device_id"],
                "trust_epoch": item["trust_epoch"],
                "nonce_client_base64url": b64url(item["nonce_client"]),
                "nonce_listener_base64url": b64url(item["nonce_listener"]),
                "client_proof_base64url": b64url(
                    compute_proof(
                        item["pair_secret"],
                        "client",
                        item["nonce_client"],
                        item["nonce_listener"],
                        item["client_device_id"],
                        item["listener_device_id"],
                        item["trust_epoch"],
                    )
                ),
                "listener_proof_base64url": b64url(
                    compute_proof(
                        item["pair_secret"],
                        "listener",
                        item["nonce_client"],
                        item["nonce_listener"],
                        item["client_device_id"],
                        item["listener_device_id"],
                        item["trust_epoch"],
                    )
                ),
                "key_client_to_listener_hex": key_c2l.hex(),
                "key_listener_to_client_hex": key_l2c.hex(),
            }
        )
    return vectors


def generate_frame_vectors(handshake_vectors: list[dict[str, object]]) -> list[dict[str, object]]:
    key_one = hashlib.sha256(b"clipsync-bt1-frame-key-1").digest()
    key_two = hashlib.sha256(b"clipsync-bt1-frame-key-2").digest()
    derived_key = bytes.fromhex(str(handshake_vectors[0]["key_client_to_listener_hex"]))

    inputs = [
        {
            "name": "sequence 0, realistic v1 ping frame",
            "key": key_one,
            "sequence": 0,
            "plaintext_utf8": (
                '{"version":1,"type":"ping","request_id":'
                '"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","body":{"sent_at_ms":1735689600000}}'
            ),
        },
        {
            "name": "sequence 1, same key, counter advanced by one",
            "key": key_one,
            "sequence": 1,
            "plaintext_utf8": (
                '{"version":1,"type":"pong","request_id":'
                '"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","body":{"sent_at_ms":1735689600123}}'
            ),
        },
        {
            "name": "sequence 2^32 pins the 64-bit big-endian counter layout",
            "key": key_one,
            "sequence": 4294967296,
            "plaintext_utf8": "counter crosses 32 bits",
        },
        {
            "name": "maximum sequence 2^64-1, the last frame a sender may emit",
            "key": key_one,
            "sequence": 18446744073709551615,
            "plaintext_utf8": "last frame before mandatory close",
        },
        {
            "name": "non-ASCII UTF-8 plaintext",
            "key": key_two,
            "sequence": 0,
            "plaintext_utf8": "剪贴板已同步 ✓",
        },
        {
            "name": "minimum one-byte plaintext",
            "key": key_two,
            "sequence": 7,
            "plaintext_utf8": "x",
        },
        {
            "name": "client-to-listener key derived from handshake vector 1",
            "key": derived_key,
            "sequence": 0,
            "plaintext_utf8": '{"hello":"bt1"}',
        },
    ]

    vectors = []
    for item in inputs:
        plaintext = item["plaintext_utf8"].encode("utf-8")
        frame = encrypt_frame(item["key"], item["sequence"], plaintext)
        vectors.append(
            {
                "name": item["name"],
                "key_hex": item["key"].hex(),
                "sequence": item["sequence"],
                "plaintext_utf8": item["plaintext_utf8"],
                "frame_hex": frame.hex(),
            }
        )
    return vectors


def write_json(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {path}")


def main() -> None:
    handshake_vectors = generate_handshake_vectors()
    write_json(
        HANDSHAKE_OUTPUT,
        {
            "algorithm": "hmac-sha256 + hkdf-sha256",
            "proof_message_format": PROOF_MESSAGE_FORMAT,
            "key_derivation": KEY_DERIVATION_FORMAT,
            "vectors": handshake_vectors,
        },
    )
    write_json(
        FRAMES_OUTPUT,
        {
            "cipher": "aes-256-gcm",
            "frame_format": FRAME_FORMAT,
            "vectors": generate_frame_vectors(handshake_vectors),
        },
    )


if __name__ == "__main__":
    main()
