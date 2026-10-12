"""Exercise the release-signing CLI without using any production private key."""

import base64
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


class ReleaseSigningTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="clipsync-signing-tests-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.key_path = self.root / "release.pem"
        self.key_path.write_bytes(self.key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
        self.public_key = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
        )).decode("ascii")
        self.source = self.root / "UpdateSignature.cs"
        self.source.write_text(f'public const string EmbeddedPublicKeySpki = "{self.public_key}";', encoding="utf-8")
        self.payload = self.root / "update.zip"
        self.payload.write_bytes(b"signed release payload\0" * 1000)
        self.script = Path(__file__).with_name("sign-release.py")

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, str(self.script), *map(str, arguments)], capture_output=True, text=True, check=False)

    def sign(self):
        return self.run_cli("sign", "--key", self.key_path, "--pub-source", self.source, self.payload)

    def test_matching_key_signs_and_verifies_the_payload(self):
        self.assertEqual(0, self.sign().returncode)
        self.assertEqual(0, self.run_cli("verify", "--pub-source", self.source, self.payload).returncode)
        signature = base64.b64decode(Path(str(self.payload) + ".sig").read_text().strip())
        # VerifyData-style hashing must accept the CLI's streamed prehashed signature.
        self.key.public_key().verify(signature, self.payload.read_bytes(), ec.ECDSA(hashes.SHA256()))

    def test_tampered_payload_is_rejected(self):
        self.assertEqual(0, self.sign().returncode)
        self.payload.write_bytes(b"replacement")
        self.assertNotEqual(0, self.run_cli("verify", "--pub", self.public_key, self.payload).returncode)

    def test_wrong_release_key_cannot_produce_an_uploadable_signature(self):
        other = ec.generate_private_key(ec.SECP256R1())
        self.key_path.write_bytes(other.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        self.assertNotEqual(0, self.sign().returncode)
        self.assertFalse(Path(str(self.payload) + ".sig").exists())

    def test_empty_embedded_key_is_rejected(self):
        self.source.write_text('public const string EmbeddedPublicKeySpki = "";', encoding="utf-8")
        self.assertNotEqual(0, self.sign().returncode)

    def test_keygen_refuses_to_replace_an_existing_key(self):
        before = self.key_path.read_bytes()
        self.assertNotEqual(0, self.run_cli("keygen", "--out", self.key_path).returncode)
        self.assertEqual(before, self.key_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
