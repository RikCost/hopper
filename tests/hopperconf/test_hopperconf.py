#!/usr/bin/env python3
"""Unit + golden-vector tests for .hopperconf (reference implementation)."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from hopperconf import (
    DEFAULT_PASSWORD,
    decrypt_envelope,
    decrypt_json_file,
    encrypt_json_file,
    encrypt_payload,
    resolved_password,
    sample_chain_payload,
    sample_key_payload,
    sample_server_payload,
)

VECTORS = Path(__file__).resolve().parent / "vectors"

# Fixed material so Android/Swift interop tests share the same ciphertext.
FIXED_SALT = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
FIXED_NONCE = bytes.fromhex("101112131415161718191a1b")
CUSTOM_PASSWORD = "test-password-🔐"


class HopperConfTests(unittest.TestCase):
    def test_default_password_is_display_name(self):
        self.assertEqual(resolved_password(None), DEFAULT_PASSWORD)
        self.assertEqual(resolved_password(""), DEFAULT_PASSWORD)
        self.assertEqual(resolved_password("  "), DEFAULT_PASSWORD)
        self.assertEqual(resolved_password("secret"), "secret")

    def test_roundtrip_server_default_password(self):
        payload = sample_server_payload()
        blob = encrypt_json_file(payload, password=None)
        decoded = decrypt_json_file(blob, password=None)
        self.assertEqual(decoded["kind"], "server")
        self.assertEqual(decoded["server"]["host"], "203.0.113.10")
        self.assertIn("PRIVATE KEY", decoded["server"]["private_key"])

    def test_roundtrip_chain_custom_password(self):
        payload = sample_chain_payload()
        blob = encrypt_json_file(payload, password=CUSTOM_PASSWORD)
        decoded = decrypt_json_file(blob, password=CUSTOM_PASSWORD)
        self.assertEqual(decoded["kind"], "chain")
        self.assertEqual(decoded["name"], "Interop Chain")
        self.assertEqual(len(decoded["hops"]), 2)

    def test_roundtrip_key_default_password(self):
        payload = sample_key_payload()
        blob = encrypt_json_file(payload, password=None)
        decoded = decrypt_json_file(blob, password=None)
        self.assertEqual(decoded["kind"], "key")
        self.assertEqual(decoded["key"]["name"], "interop-key")
        self.assertIn("PRIVATE KEY", decoded["key"]["private_key"])

    def test_wrong_password_fails(self):
        blob = encrypt_json_file(sample_server_payload(), password="a")
        with self.assertRaises(Exception):
            decrypt_json_file(blob, password="b")

    def test_default_tried_before_custom(self):
        """Files encrypted with the default open even if a wrong custom password is supplied."""
        blob = encrypt_json_file(sample_server_payload(), password=None)
        decoded = decrypt_json_file(blob, password="wrong-custom")
        self.assertEqual(decoded["server"]["name"], "interop-server")

    def test_custom_fallback_after_default_fails(self):
        blob = encrypt_json_file(sample_server_payload(), password=CUSTOM_PASSWORD)
        decoded = decrypt_json_file(blob, password=CUSTOM_PASSWORD)
        self.assertEqual(decoded["server"]["host"], "203.0.113.10")
        with self.assertRaises(Exception):
            decrypt_json_file(blob, password="not-the-custom-password")

    def test_empty_password_matches_default_string(self):
        payload = sample_server_payload()
        blob = encrypt_json_file(payload, password="")
        decoded = decrypt_json_file(blob, password=DEFAULT_PASSWORD)
        self.assertEqual(decoded["server"]["name"], "interop-server")

    def test_golden_vectors_decrypt(self):
        cases = [
            ("server_default.hopperconf", None, "server"),
            ("server_custom.hopperconf", CUSTOM_PASSWORD, "server"),
            ("chain_default.hopperconf", None, "chain"),
            ("chain_custom.hopperconf", CUSTOM_PASSWORD, "chain"),
            ("key_default.hopperconf", None, "key"),
            ("key_custom.hopperconf", CUSTOM_PASSWORD, "key"),
        ]
        for name, password, kind in cases:
            path = VECTORS / name
            self.assertTrue(path.is_file(), f"missing golden vector {path}")
            decoded = decrypt_json_file(path.read_bytes(), password=password)
            self.assertEqual(decoded["kind"], kind, name)

    def test_golden_ciphertext_stable(self):
        """Re-encrypting with fixed salt/nonce must match committed vectors."""
        server = sample_server_payload()
        chain = sample_chain_payload()
        key = sample_key_payload()
        expected = {
            "server_default.hopperconf": encrypt_json_file(
                server, None, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
            "server_custom.hopperconf": encrypt_json_file(
                server, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
            "chain_default.hopperconf": encrypt_json_file(
                chain, None, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
            "chain_custom.hopperconf": encrypt_json_file(
                chain, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
            "key_default.hopperconf": encrypt_json_file(
                key, None, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
            "key_custom.hopperconf": encrypt_json_file(
                key, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
            ),
        }
        for name, blob in expected.items():
            committed = (VECTORS / name).read_bytes()
            self.assertEqual(
                json.loads(committed),
                json.loads(blob),
                f"golden vector drift in {name} — regenerate with generate_vectors.py",
            )


def generate_vectors() -> None:
    VECTORS.mkdir(parents=True, exist_ok=True)
    server = sample_server_payload()
    chain = sample_chain_payload()
    key = sample_key_payload()
    files = {
        "server_default.hopperconf": encrypt_json_file(
            server, None, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
        "server_custom.hopperconf": encrypt_json_file(
            server, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
        "chain_default.hopperconf": encrypt_json_file(
            chain, None, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
        "chain_custom.hopperconf": encrypt_json_file(
            chain, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
        "key_default.hopperconf": encrypt_json_file(
            key, None, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
        "key_custom.hopperconf": encrypt_json_file(
            key, CUSTOM_PASSWORD, salt=FIXED_SALT, nonce=FIXED_NONCE
        ),
    }
    meta = {
        "salt_hex": FIXED_SALT.hex(),
        "nonce_hex": FIXED_NONCE.hex(),
        "default_password": DEFAULT_PASSWORD,
        "custom_password": CUSTOM_PASSWORD,
        "files": {
            "server_default.hopperconf": {"password": None, "kind": "server"},
            "server_custom.hopperconf": {"password": CUSTOM_PASSWORD, "kind": "server"},
            "chain_default.hopperconf": {"password": None, "kind": "chain"},
            "chain_custom.hopperconf": {"password": CUSTOM_PASSWORD, "kind": "chain"},
            "key_default.hopperconf": {"password": None, "kind": "key"},
            "key_custom.hopperconf": {"password": CUSTOM_PASSWORD, "kind": "key"},
        },
    }
    (VECTORS / "manifest.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    android_res = (
        Path(__file__).resolve().parents[2]
        / "app-android"
        / "app"
        / "src"
        / "test"
        / "resources"
        / "hopperconf"
    )
    android_res.mkdir(parents=True, exist_ok=True)
    for name, blob in files.items():
        (VECTORS / name).write_bytes(blob)
        (android_res / name).write_bytes(blob)
        print(f"wrote {VECTORS / name}")
        print(f"wrote {android_res / name}")


if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == "generate":
        generate_vectors()
    else:
        unittest.main()
