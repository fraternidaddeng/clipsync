#!/usr/bin/env python3
"""Sign Windows update ZIPs with ECDSA P-256 / SHA-256 (base64 DER signatures)."""

import argparse
import base64
import os
import re
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils


def spki_b64(public_key):
    return base64.b64encode(
        public_key.public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")


def load_public_key(value=None, source=None):
    if source is not None:
        match = re.search(
            r'public const string EmbeddedPublicKeySpki\s*=\s*"([^"\r\n]*)"\s*;',
            Path(source).read_text(encoding="utf-8-sig"),
        )
        if match is None:
            raise ValueError("Cannot find the client's embedded release public key.")
        value = match.group(1)
    if not value:
        raise ValueError("The client's release signing public key is not configured.")
    public_key = serialization.load_der_public_key(base64.b64decode(value, validate=True))
    if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(
        public_key.curve, ec.SECP256R1
    ):
        raise ValueError("The release public key must use ECDSA P-256.")
    return public_key


def load_private_key(path):
    key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise ValueError("The release private key must use ECDSA P-256.")
    return key


def payload_digest(path):
    digest = hashes.Hash(hashes.SHA256())
    with open(path, "rb") as payload:
        for chunk in iter(lambda: payload.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.finalize()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    keygen = commands.add_parser("keygen")
    keygen.add_argument("--out", required=True)
    sign = commands.add_parser("sign")
    sign.add_argument("--key", required=True)
    sign.add_argument("--pub-source", help="Reject keys that differ from the client's embedded key.")
    sign.add_argument("files", nargs="+")
    verify = commands.add_parser("verify")
    public_source = verify.add_mutually_exclusive_group(required=True)
    public_source.add_argument("--pub")
    public_source.add_argument("--pub-source")
    verify.add_argument("files", nargs="+")
    arguments = parser.parse_args()

    try:
        if arguments.command == "keygen":
            key = ec.generate_private_key(ec.SECP256R1())
            pem = key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            descriptor = os.open(arguments.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(pem)
            print("Private key saved outside the repository:", arguments.out)
            print("EmbeddedPublicKeySpki =", spki_b64(key.public_key()))
        elif arguments.command == "sign":
            key = load_private_key(arguments.key)
            if arguments.pub_source:
                public_key = load_public_key(source=arguments.pub_source)
                if spki_b64(key.public_key()) != spki_b64(public_key):
                    raise ValueError("Signing private key does not match the client's embedded public key.")
            for path in arguments.files:
                signature = key.sign(payload_digest(path), ec.ECDSA(utils.Prehashed(hashes.SHA256())))
                Path(path + ".sig").write_text(base64.b64encode(signature).decode("ascii") + "\n", encoding="ascii")
                print("Signed:", path + ".sig")
        else:
            public_key = load_public_key(arguments.pub, arguments.pub_source)
            for path in arguments.files:
                signature = base64.b64decode(Path(path + ".sig").read_text(encoding="ascii").strip(), validate=True)
                public_key.verify(signature, payload_digest(path), ec.ECDSA(utils.Prehashed(hashes.SHA256())))
                print("Verified:", path)
    except (OSError, ValueError, InvalidSignature) as error:
        parser.exit(1, f"Release signing failed: {str(error) or 'Invalid signature.'}\n")


if __name__ == "__main__":
    main()
