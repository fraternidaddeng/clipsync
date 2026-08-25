#!/usr/bin/env python3
"""Validate ClipSync protocol v1/v2 schemas, bt1 channel vectors, and shared fixtures."""

from __future__ import annotations

import hashlib
import hmac
import json
import struct
import sys
import base64
import uuid
from pathlib import Path
from typing import Any, Iterable

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_V1 = ROOT / "protocol" / "v1"
PROTOCOL_V2 = ROOT / "protocol" / "v2"
PROTOCOL_BT1 = ROOT / "protocol" / "bt1"
VALID = PROTOCOL_V1 / "fixtures" / "valid"
INVALID = PROTOCOL_V1 / "fixtures" / "invalid"
PAIRING_VALID = PROTOCOL_V1 / "fixtures" / "pairing" / "valid"
PAIRING_INVALID = PROTOCOL_V1 / "fixtures" / "pairing" / "invalid"
VALID_V2 = PROTOCOL_V2 / "fixtures" / "valid"
INVALID_V2 = PROTOCOL_V2 / "fixtures" / "invalid"
BT1_HANDSHAKE_VECTORS = PROTOCOL_BT1 / "fixtures" / "handshake" / "vectors.json"
BT1_FRAME_VECTORS = PROTOCOL_BT1 / "fixtures" / "frames" / "vectors.json"
BT1_HANDSHAKE_VALID = PROTOCOL_BT1 / "fixtures" / "handshake" / "valid"
BT1_HANDSHAKE_INVALID = PROTOCOL_BT1 / "fixtures" / "handshake" / "invalid"

BT1_AUTH_PREFIX = b"ClipSync/bt1/auth\n"
BT1_KEYS_INFO = b"ClipSync/bt1/keys"
BT1_MAX_PLAINTEXT_BYTES = 7 * 1024 * 1024

MAX_IMAGE_ENCODED_BYTES = 16 * 1024 * 1024
MAX_IMAGE_PIXELS = 32 * 1024 * 1024
MAX_IMAGE_SIDE = 8192
MAX_CHUNK_BYTES = 256 * 1024


class SemanticError(ValueError):
    pass


def load_json(path: Path) -> Any:
    def reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise SemanticError(f"duplicate JSON property: {key}")
            result[key] = value
        return result

    with path.open("r", encoding="utf-8", newline="") as stream:
        return json.load(stream, object_pairs_hook=reject_duplicate)


def iter_ranges(message: dict[str, Any]) -> Iterable[tuple[list[dict[str, int]], int | None]]:
    body = message["body"]
    message_type = message["type"]
    if message_type in {"want_ranges", "ack_ranges"}:
        key = "requests" if message_type == "want_ranges" else "acks"
        for entry in body[key]:
            yield entry["ranges"], None
    elif message_type in {"hello", "known_vector"}:
        vector = body["known_vector"] if message_type == "hello" else body
        for entry in vector["origins"]:
            if "received_ranges" in entry:
                yield entry["received_ranges"], entry["contiguous_seq"]


def validate_ranges(message: dict[str, Any]) -> None:
    for ranges, cursor in iter_ranges(message):
        previous_end = 0
        for index, item in enumerate(ranges):
            start = item["start_seq"]
            end = item["end_seq"]
            if start > end:
                raise SemanticError("range start_seq must be <= end_seq")
            if index and start <= previous_end + 1:
                raise SemanticError("ranges must be sorted, disjoint, and non-adjacent")
            if cursor is not None and start <= cursor + 1:
                raise SemanticError("received_ranges must start above contiguous_seq + 1")
            previous_end = end


def unique(items: Iterable[Any], label: str) -> None:
    seen: set[Any] = set()
    for item in items:
        if item in seen:
            raise SemanticError(f"duplicate {label}: {item}")
        seen.add(item)


def validate_uniqueness(message: dict[str, Any]) -> None:
    body = message["body"]
    message_type = message["type"]
    if message_type == "hello":
        origins = body["known_vector"]["origins"]
        unique((entry["origin_device_id"] for entry in origins), "origin_device_id")
    elif message_type == "known_vector":
        unique((entry["origin_device_id"] for entry in body["origins"]), "origin_device_id")
    elif message_type in {"want_ranges", "ack_ranges"}:
        key = "requests" if message_type == "want_ranges" else "acks"
        unique((entry["origin_device_id"] for entry in body[key]), "origin_device_id")
    elif message_type in {"clip_announce", "clip_payload"}:
        unique((entry["event_id"] for entry in body["clips"]), "event_id")
        unique(
            ((entry["origin_device_id"], entry["origin_seq"]) for entry in body["clips"]),
            "origin sequence",
        )
    elif message_type == "hello" and "capabilities" in body:
        unique(body["capabilities"], "capability")


def _b64url_decode(value: str) -> bytes:
    padding = "=" * ((4 - len(value) % 4) % 4)
    try:
        decoded = base64.urlsafe_b64decode(value + padding)
    except Exception as exc:
        raise SemanticError("data is not unpadded base64url") from exc
    if base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=") != value:
        raise SemanticError("data is not canonical unpadded base64url")
    return decoded


def validate_clips(message: dict[str, Any]) -> None:
    message_type = message["type"]
    if message_type not in {"clip_announce", "clip_payload"}:
        return
    total_bytes = 0
    for clip in message["body"]["clips"]:
        created = clip.get("created_at_ms")
        expires = clip.get("expires_at_ms")
        if created is not None and expires is not None and expires <= created:
            raise SemanticError("expires_at_ms must be greater than created_at_ms")
        if message_type == "clip_announce" and clip.get("availability") == "available":
            if clip.get("kind") == "image":
                width = clip["pixel_width"]
                height = clip["pixel_height"]
                if width * height > MAX_IMAGE_PIXELS:
                    raise SemanticError("image pixel count exceeds 32 MP")
                if clip["encoded_bytes"] > MAX_IMAGE_ENCODED_BYTES:
                    raise SemanticError("image encoded_bytes exceeds 16 MiB")
                if width > MAX_IMAGE_SIDE or height > MAX_IMAGE_SIDE:
                    raise SemanticError("image side exceeds 8192 px")
        if message_type != "clip_payload":
            continue
        encoded = clip["content"].encode("utf-8", errors="strict")
        if len(encoded) != clip["utf8_bytes"]:
            raise SemanticError("utf8_bytes does not equal the strict UTF-8 byte length")
        if len(encoded) > 1_048_576:
            raise SemanticError("clip text exceeds 1 MiB")
        digest = hashlib.sha256(encoded).hexdigest()
        if digest != clip["content_hash"]:
            raise SemanticError("content_hash does not match the exact UTF-8 content bytes")
        total_bytes += len(encoded)
    if total_bytes > 1_048_576:
        raise SemanticError("aggregate clip_payload text exceeds 1 MiB")


def validate_image_chunks(message: dict[str, Any]) -> None:
    message_type = message["type"]
    body = message["body"]
    if message_type == "clip_payload_begin":
        if body["encoded_bytes"] > MAX_IMAGE_ENCODED_BYTES:
            raise SemanticError("begin encoded_bytes exceeds 16 MiB")
        if body["chunk_count"] < 1:
            raise SemanticError("chunk_count must be at least 1")
        return
    if message_type != "clip_payload_chunk":
        return
    if body["chunk_index"] >= body["chunk_count"]:
        raise SemanticError("chunk_index must be less than chunk_count")
    if "=" in body["data"] or "+" in body["data"] or "/" in body["data"]:
        raise SemanticError("chunk data must be unpadded base64url")
    decoded = _b64url_decode(body["data"])
    if len(decoded) != body["chunk_bytes"]:
        raise SemanticError("chunk_bytes does not match decoded data length")
    if len(decoded) > MAX_CHUNK_BYTES:
        raise SemanticError("chunk exceeds 256 KiB")


def validate_semantics(message: dict[str, Any]) -> None:
    validate_ranges(message)
    validate_uniqueness(message)
    validate_clips(message)
    validate_image_chunks(message)


def _b64url_decode_exact(value: str, expected_length: int, label: str) -> bytes:
    decoded = _b64url_decode(value)
    if len(decoded) != expected_length:
        raise SemanticError(f"{label} must decode to exactly {expected_length} bytes")
    return decoded


def validate_bt1_handshake_semantics(message: dict[str, Any]) -> None:
    kind = message["kind"]
    if kind in {"bt1_client_hello", "bt1_listener_hello"}:
        _b64url_decode_exact(message["nonce"], 32, "nonce")
    elif kind in {"bt1_client_auth", "bt1_listener_auth"}:
        _b64url_decode_exact(message["proof"], 32, "proof")


def _bt1_compute_proof(
    pair_secret: bytes,
    role: str,
    nonce_client: bytes,
    nonce_listener: bytes,
    client_device_id: str,
    listener_device_id: str,
    trust_epoch: int,
) -> bytes:
    message = (
        BT1_AUTH_PREFIX
        + role.encode("utf-8")
        + b"\x00"
        + nonce_client
        + nonce_listener
        + uuid.UUID(client_device_id).bytes
        + uuid.UUID(listener_device_id).bytes
        + struct.pack(">q", trust_epoch)
    )
    return hmac.new(pair_secret, message, hashlib.sha256).digest()


def _bt1_hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = b""
    block = b""
    counter = 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def check_bt1_handshake_vectors(failures: list[str]) -> int:
    document = load_json(BT1_HANDSHAKE_VECTORS)
    vectors = document["vectors"]
    if not vectors:
        failures.append("bt1 handshake vector set must not be empty")
    unique((vector["name"] for vector in vectors), "bt1 handshake vector name")
    for vector in vectors:
        name = vector["name"]
        try:
            pair_secret = bytes.fromhex(vector["pair_secret_hex"])
            nonce_client = _b64url_decode_exact(vector["nonce_client_base64url"], 32, "nonce_client")
            nonce_listener = _b64url_decode_exact(vector["nonce_listener_base64url"], 32, "nonce_listener")
            if len(pair_secret) != 32:
                raise SemanticError("pair_secret must be 32 bytes")
            expected = {
                "client_proof_base64url": _bt1_compute_proof(
                    pair_secret, "client", nonce_client, nonce_listener,
                    vector["client_device_id"], vector["listener_device_id"], vector["trust_epoch"],
                ),
                "listener_proof_base64url": _bt1_compute_proof(
                    pair_secret, "listener", nonce_client, nonce_listener,
                    vector["client_device_id"], vector["listener_device_id"], vector["trust_epoch"],
                ),
            }
            for field, proof in expected.items():
                encoded = base64.urlsafe_b64encode(proof).decode("ascii").rstrip("=")
                if vector[field] != encoded:
                    raise SemanticError(f"{field} does not match the recomputed proof")
            okm = _bt1_hkdf_sha256(pair_secret, nonce_client + nonce_listener, BT1_KEYS_INFO, 64)
            if vector["key_client_to_listener_hex"] != okm[:32].hex():
                raise SemanticError("key_client_to_listener_hex does not match HKDF output")
            if vector["key_listener_to_client_hex"] != okm[32:].hex():
                raise SemanticError("key_listener_to_client_hex does not match HKDF output")
        except Exception as exc:
            failures.append(f"bt1 handshake vector failed: {name}: {exc}")
    return len(vectors)


def check_bt1_frame_vectors(failures: list[str]) -> int:
    document = load_json(BT1_FRAME_VECTORS)
    vectors = document["vectors"]
    if not vectors:
        failures.append("bt1 frame vector set must not be empty")
    unique((vector["name"] for vector in vectors), "bt1 frame vector name")
    for vector in vectors:
        name = vector["name"]
        try:
            key = bytes.fromhex(vector["key_hex"])
            if len(key) != 32:
                raise SemanticError("key must be 32 bytes")
            sequence = vector["sequence"]
            if not 0 <= sequence <= 0xFFFFFFFFFFFFFFFF:
                raise SemanticError("sequence must fit an unsigned 64-bit counter")
            plaintext = vector["plaintext_utf8"].encode("utf-8", errors="strict")
            if not 1 <= len(plaintext) <= BT1_MAX_PLAINTEXT_BYTES:
                raise SemanticError("plaintext must be 1 byte to 7 MiB")
            nonce = b"\x00" * 4 + struct.pack(">Q", sequence)
            ciphertext = AESGCM(key).encrypt(nonce, plaintext, None)
            frame = struct.pack(">I", len(ciphertext)) + ciphertext
            if vector["frame_hex"] != frame.hex():
                raise SemanticError("frame_hex does not match the recomputed frame")
        except Exception as exc:
            failures.append(f"bt1 frame vector failed: {name}: {exc}")
    return len(vectors)


def check_fixtures(
    label: str,
    validator: Draft202012Validator,
    valid_dir: Path,
    invalid_dir: Path,
    semantics,
    failures: list[str],
) -> tuple[int, int]:
    valid_paths = sorted(valid_dir.glob("*.json"))
    invalid_paths = sorted(invalid_dir.glob("*.json"))
    if not valid_paths or not invalid_paths:
        failures.append(f"{label} fixture sets must both be non-empty")

    for path in valid_paths:
        try:
            instance = load_json(path)
            validator.validate(instance)
            semantics(instance)
        except Exception as exc:  # report all fixture failures in one run
            failures.append(f"{label} valid fixture rejected: {path.name}: {exc}")

    for path in invalid_paths:
        try:
            instance = load_json(path)
            validator.validate(instance)
            semantics(instance)
        except Exception:
            continue
        failures.append(f"{label} invalid fixture accepted: {path.name}")

    return len(valid_paths), len(invalid_paths)


def build_validator(protocol_dir: Path) -> Draft202012Validator:
    envelope_schema = load_json(protocol_dir / "envelope.schema.json")
    messages_schema = load_json(protocol_dir / "messages.schema.json")
    resources = [
        (envelope_schema["$id"], Resource.from_contents(envelope_schema)),
        (messages_schema["$id"], Resource.from_contents(messages_schema)),
    ]
    pairing_path = protocol_dir / "pairing.schema.json"
    if pairing_path.exists():
        pairing_schema = load_json(pairing_path)
        resources.append((pairing_schema["$id"], Resource.from_contents(pairing_schema)))
    registry = Registry().with_resources(resources)
    return Draft202012Validator(envelope_schema, registry=registry)


def main() -> int:
    envelope_validator = build_validator(PROTOCOL_V1)
    envelope_v2_validator = build_validator(PROTOCOL_V2)
    pairing_schema = load_json(PROTOCOL_V1 / "pairing.schema.json")
    pairing_registry = Registry().with_resources(
        [(pairing_schema["$id"], Resource.from_contents(pairing_schema))]
    )
    pairing_validator = Draft202012Validator(pairing_schema, registry=pairing_registry)

    bt1_schema = load_json(PROTOCOL_BT1 / "handshake.schema.json")
    bt1_registry = Registry().with_resources(
        [(bt1_schema["$id"], Resource.from_contents(bt1_schema))]
    )
    bt1_validator = Draft202012Validator(bt1_schema, registry=bt1_registry)

    failures: list[str] = []
    valid_count, invalid_count = check_fixtures(
        "envelope v1", envelope_validator, VALID, INVALID, validate_semantics, failures
    )
    pairing_valid, pairing_invalid = check_fixtures(
        "pairing", pairing_validator, PAIRING_VALID, PAIRING_INVALID, lambda _: None, failures
    )
    valid_v2, invalid_v2 = check_fixtures(
        "envelope v2", envelope_v2_validator, VALID_V2, INVALID_V2, validate_semantics, failures
    )
    bt1_valid, bt1_invalid = check_fixtures(
        "bt1 handshake",
        bt1_validator,
        BT1_HANDSHAKE_VALID,
        BT1_HANDSHAKE_INVALID,
        validate_bt1_handshake_semantics,
        failures,
    )
    bt1_handshake_vectors = check_bt1_handshake_vectors(failures)
    bt1_frame_vectors = check_bt1_frame_vectors(failures)

    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    print(
        f"Validated {valid_count} valid and {invalid_count} invalid protocol v1 fixtures, "
        f"{valid_v2} valid and {invalid_v2} invalid protocol v2 fixtures, "
        f"plus {pairing_valid} valid and {pairing_invalid} invalid pairing fixtures, "
        f"plus {bt1_valid} valid and {bt1_invalid} invalid bt1 handshake fixtures, "
        f"{bt1_handshake_vectors} bt1 handshake vectors, and {bt1_frame_vectors} bt1 frame vectors."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
