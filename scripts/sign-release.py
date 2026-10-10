#!/usr/bin/env python3
"""Release signing for the Windows updater (ECDSA P-256 / SHA-256, DER signature, base64).

  python scripts/sign-release.py keygen  --out ~/clipsync-release-key.pem   # once; NEVER commit
  python scripts/sign-release.py sign    --key ~/clipsync-release-key.pem ClipSync-windows-x64.zip
  python scripts/sign-release.py verify  --pub <base64 spki> ClipSync-windows-x64.zip

keygen prints the base64 SubjectPublicKeyInfo to paste into
windows/ClipSync.Core/Update/UpdateSignature.cs (EmbeddedPublicKeySpki).
sign writes <file>.sig next to the payload; upload it as a release asset.
Requires: pip install cryptography
"""
import argparse, base64, os, sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def spki_b64(pub):
    return base64.b64encode(pub.public_bytes(serialization.Encoding.DER,
                                             serialization.PublicFormat.SubjectPublicKeyInfo)).decode()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen"); k.add_argument("--out", required=True)
    s = sub.add_parser("sign"); s.add_argument("--key", required=True); s.add_argument("files", nargs="+")
    v = sub.add_parser("verify"); v.add_argument("--pub", required=True); v.add_argument("files", nargs="+")
    a = ap.parse_args()
    if a.cmd == "keygen":
        if os.path.exists(a.out):
            sys.exit(f"refusing to overwrite {a.out}")
        key = ec.generate_private_key(ec.SECP256R1())
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption())
        fd = os.open(a.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(pem)
        print("private key:", a.out, "(keep offline / in a CI secret, never commit)")
        print("EmbeddedPublicKeySpki =", spki_b64(key.public_key()))
    elif a.cmd == "sign":
        with open(a.key, "rb") as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
        for path in a.files:
            with open(path, "rb") as f:
                sig = key.sign(f.read(), ec.ECDSA(hashes.SHA256()))
            with open(path + ".sig", "w") as f:
                f.write(base64.b64encode(sig).decode() + "\n")
            print("signed", path + ".sig")
    else:
        pub = serialization.load_der_public_key(base64.b64decode(a.pub))
        for path in a.files:
            with open(path, "rb") as f, open(path + ".sig") as g:
                pub.verify(base64.b64decode(g.read().strip()), f.read(), ec.ECDSA(hashes.SHA256()))
            print("ok", path)


if __name__ == "__main__":
    main()
