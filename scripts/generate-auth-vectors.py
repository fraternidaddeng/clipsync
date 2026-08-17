#!/usr/bin/env python3
"""Generate protocol v1 pair-auth HMAC test vectors (independent reference implementation).

The output file protocol/v1/fixtures/auth/vectors.json is frozen; rerunning this script
must be a no-op unless the protocol version changes. Both clients must reproduce these
proofs bit-for-bit. See docs/protocol-v1.md section 3 for the message layout.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import struct
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "protocol" / "v1" / "fixtures" / "auth" / "vectors.json"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def compute_proof(
    pair_secret: bytes,
    challenge_request_id: str,
    nonce: bytes,
    challenger_device_id: str,
    responder_device_id: str,
    trust_epoch: int,
) -> bytes:
    message = (
        "ClipSync/v1/auth\n".encode("utf-8")
        + challenge_request_id.encode("utf-8")
        + b"\x00"
        + nonce
        + uuid.UUID(challenger_device_id).bytes
        + uuid.UUID(responder_device_id).bytes
        + struct.pack(">q", trust_epoch)
    )
    return hmac.new(pair_secret, message, hashlib.sha256).digest()


def main() -> None:
    inputs = [
        {
            "name": "sequential secret, zero nonce, epoch 1",
            "pair_secret_hex": bytes(range(32)).hex(),
            "challenge_request_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "nonce": bytes(32),
            "challenger_device_id": "11111111-1111-4111-8111-111111111111",
            "responder_device_id": "22222222-2222-4222-8222-222222222222",
            "trust_epoch": 1,
        },
        {
            "name": "all-ones secret, sequential nonce, max epoch",
            "pair_secret_hex": (b"\xff" * 32).hex(),
            "challenge_request_id": "0f0e0d0c-0b0a-4908-8706-050403020100",
            "nonce": bytes(range(32)),
            "challenger_device_id": "fedcba98-7654-4321-8765-432187654321",
            "responder_device_id": "01234567-89ab-4def-8123-456789abcdef",
            "trust_epoch": 9223372036854775807,
        },
        {
            "name": "mixed secret, epoch 42",
            "pair_secret_hex": hashlib.sha256(b"clipsync-vector-3").hexdigest(),
            "challenge_request_id": "c0ffee00-1234-4abc-9def-000000000042",
            "nonce": hashlib.sha256(b"clipsync-nonce-3").digest(),
            "challenger_device_id": "aaaabbbb-cccc-4ddd-8eee-ffff00001111",
            "responder_device_id": "99998888-7777-4666-b555-444433332222",
            "trust_epoch": 42,
        },
    ]

    vectors = []
    for item in inputs:
        proof = compute_proof(
            bytes.fromhex(item["pair_secret_hex"]),
            item["challenge_request_id"],
            item["nonce"],
            item["challenger_device_id"],
            item["responder_device_id"],
            item["trust_epoch"],
        )
        vectors.append(
            {
                "name": item["name"],
                "pair_secret_hex": item["pair_secret_hex"],
                "challenge_request_id": item["challenge_request_id"],
                "nonce_base64url": b64url(item["nonce"]),
                "challenger_device_id": item["challenger_device_id"],
                "responder_device_id": item["responder_device_id"],
                "trust_epoch": item["trust_epoch"],
                "proof_base64url": b64url(proof),
            }
        )

    document = {
        "algorithm": "hmac-sha256",
        "message_format": (
            "UTF8('ClipSync/v1/auth\\n') || UTF8(challenge_request_id) || 0x00 || nonce_bytes || "
            "UUID_BYTES(challenger_device_id) || UUID_BYTES(responder_device_id) || INT64_BE(trust_epoch)"
        ),
        "vectors": vectors,
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()
