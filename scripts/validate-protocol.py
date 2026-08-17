#!/usr/bin/env python3
"""Validate ClipSync protocol v1 schemas and shared fixtures."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Iterable

from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "protocol" / "v1"
VALID = PROTOCOL / "fixtures" / "valid"
INVALID = PROTOCOL / "fixtures" / "invalid"
PAIRING_VALID = PROTOCOL / "fixtures" / "pairing" / "valid"
PAIRING_INVALID = PROTOCOL / "fixtures" / "pairing" / "invalid"


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


def validate_semantics(message: dict[str, Any]) -> None:
    validate_ranges(message)
    validate_uniqueness(message)
    validate_clips(message)


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


def main() -> int:
    envelope_schema = load_json(PROTOCOL / "envelope.schema.json")
    messages_schema = load_json(PROTOCOL / "messages.schema.json")
    pairing_schema = load_json(PROTOCOL / "pairing.schema.json")
    registry = Registry().with_resources(
        [
            (envelope_schema["$id"], Resource.from_contents(envelope_schema)),
            (messages_schema["$id"], Resource.from_contents(messages_schema)),
            (pairing_schema["$id"], Resource.from_contents(pairing_schema)),
        ]
    )
    envelope_validator = Draft202012Validator(envelope_schema, registry=registry)
    pairing_validator = Draft202012Validator(pairing_schema, registry=registry)

    failures: list[str] = []
    valid_count, invalid_count = check_fixtures(
        "envelope", envelope_validator, VALID, INVALID, validate_semantics, failures
    )
    pairing_valid, pairing_invalid = check_fixtures(
        "pairing", pairing_validator, PAIRING_VALID, PAIRING_INVALID, lambda _: None, failures
    )

    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    print(
        f"Validated {valid_count} valid and {invalid_count} invalid protocol v1 fixtures, "
        f"plus {pairing_valid} valid and {pairing_invalid} invalid pairing fixtures."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
