#!/usr/bin/env python3
"""Reference implementation of Hopper .hopperconf encrypt/decrypt (v1).

Matches iOS (CommonCrypto PBKDF2 + CryptoKit AES-GCM) and Android
(BouncyCastle PKCS5S2 + AES/GCM/NoPadding).
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
from typing import Any, Optional

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

FMT = "hopperconf"
ALG = "aes-256-gcm"
KDF = "pbkdf2-sha256"
ENVELOPE_VERSION = 1
PAYLOAD_VERSION = 1
PBKDF2_ITERATIONS = 210_000
SALT_LENGTH = 16
NONCE_LENGTH = 12
KEY_LENGTH = 32
DEFAULT_PASSWORD = "ɹǝddoH"  # HopConstants.appDisplayName / APP_DISPLAY_NAME


def resolved_password(password: Optional[str]) -> str:
    trimmed = (password or "").strip()
    return trimmed if trimmed else DEFAULT_PASSWORD


def derive_key(password: str, salt: bytes, iterations: int = PBKDF2_ITERATIONS) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH,
        salt=salt,
        iterations=iterations,
    )
    return kdf.derive(password.encode("utf-8"))


def encrypt_payload(
    plaintext: bytes,
    password: Optional[str] = None,
    *,
    salt: Optional[bytes] = None,
    nonce: Optional[bytes] = None,
    iterations: int = PBKDF2_ITERATIONS,
) -> dict[str, Any]:
    salt = salt if salt is not None else os.urandom(SALT_LENGTH)
    nonce = nonce if nonce is not None else os.urandom(NONCE_LENGTH)
    if len(salt) != SALT_LENGTH:
        raise ValueError(f"salt must be {SALT_LENGTH} bytes")
    if len(nonce) != NONCE_LENGTH:
        raise ValueError(f"nonce must be {NONCE_LENGTH} bytes")
    key = derive_key(resolved_password(password), salt, iterations)
    cipher_and_tag = AESGCM(key).encrypt(nonce, plaintext, None)
    return {
        "v": ENVELOPE_VERSION,
        "fmt": FMT,
        "alg": ALG,
        "kdf": KDF,
        "iter": iterations,
        "salt": base64.b64encode(salt).decode("ascii"),
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "data": base64.b64encode(cipher_and_tag).decode("ascii"),
    }


def decrypt_envelope(envelope: dict[str, Any], password: Optional[str] = None) -> bytes:
    if envelope.get("fmt") != FMT:
        raise ValueError("Not a valid .hopperconf file.")
    salt = base64.b64decode(envelope["salt"])
    nonce = base64.b64decode(envelope["nonce"])
    cipher_and_tag = base64.b64decode(envelope["data"])
    iterations = int(envelope.get("iter", PBKDF2_ITERATIONS))

    # Prefer the default password first; fall back to a user-provided password.
    candidates: list[str] = [DEFAULT_PASSWORD]
    provided = (password or "").strip()
    if provided and provided != DEFAULT_PASSWORD:
        candidates.append(provided)

    last_error: Exception | None = None
    for candidate in candidates:
        try:
            key = derive_key(candidate, salt, iterations)
            return AESGCM(key).decrypt(nonce, cipher_and_tag, None)
        except Exception as exc:  # noqa: BLE001 — try next candidate
            last_error = exc
    raise ValueError("Could not decrypt — check the password.") from last_error


def encrypt_json_file(
    payload: dict[str, Any],
    password: Optional[str] = None,
    **kwargs: Any,
) -> bytes:
    plaintext = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    envelope = encrypt_payload(plaintext, password, **kwargs)
    return (json.dumps(envelope, indent=2, sort_keys=True) + "\n").encode("utf-8")


def decrypt_json_file(data: bytes, password: Optional[str] = None) -> dict[str, Any]:
    envelope = json.loads(data.decode("utf-8"))
    plaintext = decrypt_envelope(envelope, password)
    return json.loads(plaintext.decode("utf-8"))


def sample_server_payload() -> dict[str, Any]:
    return {
        "v": PAYLOAD_VERSION,
        "kind": "server",
        "server": {
            "v": 2,
            "name": "interop-server",
            "host": "203.0.113.10",
            "port": "22",
            "user": "root",
            "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nTESTKEY\n-----END OPENSSH PRIVATE KEY-----",
            "install_dir": "~/hopper",
            "server_version": "2.5.0",
            "min_app_version": "2.5.0",
            "host_key": ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIInteropTestHostKey"],
        },
    }


def sample_chain_payload() -> dict[str, Any]:
    hop_a = sample_server_payload()["server"]
    hop_b = dict(hop_a)
    hop_b["name"] = "interop-exit"
    hop_b["host"] = "198.51.100.20"
    return {
        "v": PAYLOAD_VERSION,
        "kind": "chain",
        "name": "Interop Chain",
        "hops": [hop_a, hop_b],
    }


def sample_key_payload() -> dict[str, Any]:
    return {
        "v": PAYLOAD_VERSION,
        "kind": "key",
        "key": {
            "name": "interop-key",
            "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nTESTKEY\n-----END OPENSSH PRIVATE KEY-----",
        },
    }
