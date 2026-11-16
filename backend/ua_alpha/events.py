"""Signed, transport-neutral event envelopes and replay-safe local ingestion."""
from __future__ import annotations

import hashlib
import json
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .database import Database
from .identity import PersonaKeypair, _b64, _canonical, _unb64

EVENT_SCHEMA = "ua.event.v1"
MAX_EVENT_BYTES = 64 * 1024
MAX_PAYLOAD_BYTES = 48 * 1024


class EventError(ValueError):
    """An event is malformed, untrusted, or conflicts with stored state."""


@dataclass(frozen=True)
class SignedEvent:
    event_id: str
    event_type: str
    schema: str
    persona_id: str
    public_key: str
    created_at: str
    payload: dict[str, Any]
    signature: str

    def unsigned(self) -> dict[str, Any]:
        return {
            "event_id": self.event_id,
            "event_type": self.event_type,
            "schema": self.schema,
            "persona_id": self.persona_id,
            "public_key": self.public_key,
            "created_at": self.created_at,
            "payload": self.payload,
        }

    def to_dict(self) -> dict[str, Any]:
        return {**self.unsigned(), "signature": self.signature}

    def to_bytes(self) -> bytes:
        return _canonical(self.to_dict())

    @classmethod
    def create(
        cls, *, event_type: str, persona_id: str, keypair: PersonaKeypair,
        payload: dict[str, Any], created_at: str | None = None,
    ) -> "SignedEvent":
        if not event_type or len(event_type) > 128:
            raise EventError("event_type must contain 1–128 characters")
        if not persona_id or len(persona_id) > 128:
            raise EventError("persona_id must contain 1–128 characters")
        if not isinstance(payload, dict):
            raise EventError("payload must be a JSON object")
        payload_bytes = _canonical(payload)
        if len(payload_bytes) > MAX_PAYLOAD_BYTES:
            raise EventError("event payload exceeds the size limit")
        timestamp = created_at or datetime.now(timezone.utc).isoformat(timespec="seconds")
        try:
            parsed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        except ValueError as exc:
            raise EventError("created_at must be an ISO-8601 timestamp") from exc
        if parsed.tzinfo is None:
            raise EventError("created_at must include a timezone")
        public_key = _b64(keypair.public_key_bytes)
        base = {
            "event_type": event_type,
            "schema": EVENT_SCHEMA,
            "persona_id": persona_id,
            "public_key": public_key,
            "created_at": timestamp,
            "payload": payload,
        }
        event_id = hashlib.sha256(_canonical(base)).hexdigest()
        unsigned = {
            "event_id": event_id,
            "event_type": event_type,
            "schema": EVENT_SCHEMA,
            "persona_id": persona_id,
            "public_key": public_key,
            "created_at": timestamp,
            "payload": payload,
        }
        signature = keypair.sign(_canonical(unsigned))
        return cls(signature=signature, **unsigned)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "SignedEvent":
        required = {"event_id", "event_type", "schema", "persona_id", "public_key", "created_at", "payload", "signature"}
        if set(value) != required:
            raise EventError("event has missing or unexpected fields")
        if not isinstance(value["payload"], dict):
            raise EventError("event payload must be an object")
        try:
            event = cls(**dict(value))
        except TypeError as exc:
            raise EventError("event fields have invalid types") from exc
        if len(event.to_bytes()) > MAX_EVENT_BYTES:
            raise EventError("event exceeds the size limit")
        return event

    def verify(self, trusted_personas: Mapping[str, bytes]) -> bool:
        """Verify signature and an independently established persona-key binding."""
        trusted_key = trusted_personas.get(self.persona_id)
        if trusted_key is None:
            return False
        try:
            supplied_key = _unb64(self.public_key)
            if supplied_key != trusted_key:
                return False
            if self.schema != EVENT_SCHEMA:
                return False
            if hashlib.sha256(_canonical({
                "event_type": self.event_type,
                "schema": self.schema,
                "persona_id": self.persona_id,
                "public_key": self.public_key,
                "created_at": self.created_at,
                "payload": self.payload,
            })).hexdigest() != self.event_id:
                return False
            Ed25519PublicKey.from_public_bytes(trusted_key).verify(
                _unb64(self.signature), _canonical(self.unsigned())
            )
            return True
        except (InvalidSignature, ValueError, TypeError):
            return False


class EventStore:
    """Append-only inbox/outbox table; ingestion is idempotent by event_id."""

    def __init__(self, database: Database) -> None:
        self.db = database

    def ingest(self, event: SignedEvent, trusted_personas: Mapping[str, bytes]) -> bool:
        if not event.verify(trusted_personas):
            raise EventError("event signature or persona binding is not trusted")
        encoded = event.to_bytes().decode("utf-8")
        with self.db.transaction() as conn:
            existing = conn.execute("SELECT event_json FROM event_records WHERE event_id = ?", (event.event_id,)).fetchone()
            if existing:
                if existing["event_json"] != encoded:
                    raise EventError("event ID collision with different content")
                return False
            conn.execute(
                """INSERT INTO event_records(event_id, persona_id, event_type, created_at, event_json)
                   VALUES (?, ?, ?, ?, ?)""",
                (event.event_id, event.persona_id, event.event_type, event.created_at, encoded),
            )
        return True

    def get(self, event_id: str) -> SignedEvent | None:
        row = self.db.connection.execute(
            "SELECT event_json FROM event_records WHERE event_id = ?", (event_id,)
        ).fetchone()
        if row is None:
            return None
        return SignedEvent.from_dict(json.loads(row["event_json"]))

    def export_after(self, after_event_id: str | None = None, limit: int = 100) -> list[SignedEvent]:
        if not 1 <= limit <= 500:
            raise EventError("limit must be between 1 and 500")
        if after_event_id is None:
            rows = self.db.connection.execute(
                "SELECT event_json FROM event_records ORDER BY created_at, event_id LIMIT ?", (limit,)
            ).fetchall()
        else:
            cursor = self.db.connection.execute(
                "SELECT created_at, event_id FROM event_records WHERE event_id = ?", (after_event_id,)
            ).fetchone()
            if cursor is None:
                raise EventError("unknown synchronization cursor")
            rows = self.db.connection.execute(
                """SELECT event_json FROM event_records
                   WHERE (created_at, event_id) > (?, ?) ORDER BY created_at, event_id LIMIT ?""",
                (cursor["created_at"], cursor["event_id"], limit),
            ).fetchall()
        return [SignedEvent.from_dict(json.loads(row["event_json"])) for row in rows]
