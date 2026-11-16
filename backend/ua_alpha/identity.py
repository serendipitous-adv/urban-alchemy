"""Transport-independent UA persona signing primitives.

Persona keys are independent Ed25519 keys. They are not derived from a
Reticulum identity, browser credential, or offline recovery key.
"""
from __future__ import annotations

import base64
import hashlib
import json
import uuid
from dataclasses import dataclass

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _unb64(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


@dataclass(frozen=True)
class PersonaKeypair:
    """An in-memory Ed25519 keypair. Persist private keys only in secure storage."""

    private_key: Ed25519PrivateKey

    @classmethod
    def generate(cls) -> "PersonaKeypair":
        return cls(Ed25519PrivateKey.generate())

    @property
    def public_key_bytes(self) -> bytes:
        return self.private_key.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        )

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(self.public_key_bytes).hexdigest()

    def sign(self, message: bytes) -> str:
        return _b64(self.private_key.sign(message))


@dataclass(frozen=True)
class Persona:
    """Stable UA persona identifier plus its current signing public key."""

    persona_id: str
    public_key: bytes

    @classmethod
    def create(cls, keypair: PersonaKeypair) -> "Persona":
        return cls(persona_id=str(uuid.uuid4()), public_key=keypair.public_key_bytes)

    @property
    def key_fingerprint(self) -> str:
        return hashlib.sha256(self.public_key).hexdigest()

    def verify(self, message: bytes, signature: str) -> bool:
        try:
            Ed25519PublicKey.from_public_bytes(self.public_key).verify(_unb64(signature), message)
            return True
        except (InvalidSignature, ValueError):
            return False


@dataclass(frozen=True)
class KeyRotation:
    """A signed continuity statement from one persona signing key to its successor."""

    persona_id: str
    sequence: int
    previous_fingerprint: str
    next_public_key: str
    signature: str

    def payload(self) -> dict:
        return {
            "kind": "ua.persona-key-rotation.v1",
            "persona_id": self.persona_id,
            "sequence": self.sequence,
            "previous_fingerprint": self.previous_fingerprint,
            "next_public_key": self.next_public_key,
        }


def create_key_rotation(
    persona: Persona,
    current_key: PersonaKeypair,
    next_key: PersonaKeypair,
    sequence: int,
) -> KeyRotation:
    """Create a continuity statement; caller must supply the current persona key."""
    if sequence < 1:
        raise ValueError("rotation sequence must be positive")
    if current_key.public_key_bytes != persona.public_key:
        raise ValueError("current key does not match persona's registered key")
    next_public = _b64(next_key.public_key_bytes)
    unsigned = KeyRotation(
        persona_id=persona.persona_id,
        sequence=sequence,
        previous_fingerprint=persona.key_fingerprint,
        next_public_key=next_public,
        signature="",
    )
    return KeyRotation(**{**unsigned.__dict__, "signature": current_key.sign(_canonical(unsigned.payload()))})


def verify_key_rotation(rotation: KeyRotation, previous_public_key: bytes) -> bool:
    """Verify continuity from the supplied previous key; does not establish authority."""
    expected = hashlib.sha256(previous_public_key).hexdigest()
    if rotation.sequence < 1 or rotation.previous_fingerprint != expected:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(previous_public_key).verify(
            _unb64(rotation.signature), _canonical(rotation.payload())
        )
        next_public = _unb64(rotation.next_public_key)
        Ed25519PublicKey.from_public_bytes(next_public)
        return True
    except (InvalidSignature, ValueError):
        return False


def persona_from_rotation(rotation: KeyRotation) -> Persona:
    """Return the next-key persona view after an external policy accepts the rotation."""
    return Persona(persona_id=rotation.persona_id, public_key=_unb64(rotation.next_public_key))
