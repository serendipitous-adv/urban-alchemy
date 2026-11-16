"""Signed peer invitations and persistent, least-privilege trust grants.

Invitations are transport-independent. Delivery (including future LXMF delivery)
does not confer trust: the recipient must verify a previously trusted inviter key
and explicitly accept the exact signed scopes.
"""
from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterable, Mapping

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .database import Database
from .identity import PersonaKeypair, _b64, _canonical, _unb64


class InvitationError(ValueError):
    """An invitation is malformed, expired, replayed, or unauthorized."""


class UnknownInviter(InvitationError):
    """The inviter key has not been independently trusted by this recipient."""


def _timestamp(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as exc:
        raise InvitationError("timestamp must be ISO-8601") from exc
    if parsed.tzinfo is None:
        raise InvitationError("timestamp must include a timezone")
    return parsed.astimezone(timezone.utc)


@dataclass(frozen=True)
class PeerInvitation:
    invitation_id: str
    inviter_persona_id: str
    inviter_public_key: str
    recipient_persona_id: str | None
    scopes: tuple[str, ...]
    issued_at: str
    expires_at: str
    nonce: str
    signature: str

    def unsigned(self) -> dict:
        return {
            "protocol": "ua.peer-invitation.v1",
            "invitation_id": self.invitation_id,
            "inviter_persona_id": self.inviter_persona_id,
            "inviter_public_key": self.inviter_public_key,
            "recipient_persona_id": self.recipient_persona_id,
            "scopes": list(self.scopes),
            "issued_at": self.issued_at,
            "expires_at": self.expires_at,
            "nonce": self.nonce,
        }

    def to_dict(self) -> dict:
        return {**self.unsigned(), "signature": self.signature}

    def to_bytes(self) -> bytes:
        return _canonical(self.to_dict())

    @classmethod
    def create(
        cls, *, inviter_persona_id: str, keypair: PersonaKeypair,
        scopes: Iterable[str], recipient_persona_id: str | None = None,
        ttl: timedelta = timedelta(days=7), now: datetime | None = None,
    ) -> "PeerInvitation":
        normalized = tuple(sorted(set(scopes)))
        if not inviter_persona_id or len(inviter_persona_id) > 128:
            raise InvitationError("inviter persona ID is required")
        if not normalized or len(normalized) > 32:
            raise InvitationError("an invitation must contain 1–32 scopes")
        if any(not isinstance(s, str) or not s or len(s) > 128 for s in normalized):
            raise InvitationError("scope names must contain 1–128 characters")
        if recipient_persona_id is not None and (not recipient_persona_id or len(recipient_persona_id) > 128):
            raise InvitationError("recipient persona ID is invalid")
        if ttl <= timedelta(0) or ttl > timedelta(days=30):
            raise InvitationError("invitation lifetime must be greater than zero and at most 30 days")
        issued = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        expires = issued + ttl
        public = _b64(keypair.public_key_bytes)
        unsigned = cls(
            invitation_id="", inviter_persona_id=inviter_persona_id,
            inviter_public_key=public, recipient_persona_id=recipient_persona_id,
            scopes=normalized, issued_at=issued.isoformat(), expires_at=expires.isoformat(),
            nonce=_b64(__import__("secrets").token_bytes(24)), signature="",
        )
        identity = hashlib.sha256(_canonical(unsigned.unsigned())).hexdigest()
        signed = cls(**{**unsigned.__dict__, "invitation_id": identity})
        return cls(**{**signed.__dict__, "signature": keypair.sign(_canonical(signed.unsigned()))})

    @classmethod
    def from_dict(cls, value: Mapping) -> "PeerInvitation":
        required = {
            "protocol", "invitation_id", "inviter_persona_id", "inviter_public_key",
            "recipient_persona_id", "scopes", "issued_at", "expires_at", "nonce", "signature",
        }
        if set(value) != required or value.get("protocol") != "ua.peer-invitation.v1":
            raise InvitationError("invitation fields or protocol are invalid")
        if not isinstance(value["scopes"], list) or not all(isinstance(s, str) for s in value["scopes"]):
            raise InvitationError("invitation scopes are malformed")
        try:
            invitation = cls(
                invitation_id=value["invitation_id"], inviter_persona_id=value["inviter_persona_id"],
                inviter_public_key=value["inviter_public_key"], recipient_persona_id=value["recipient_persona_id"],
                scopes=tuple(value["scopes"]), issued_at=value["issued_at"], expires_at=value["expires_at"],
                nonce=value["nonce"], signature=value["signature"],
            )
        except (TypeError, KeyError) as exc:
            raise InvitationError("invitation fields are malformed") from exc
        if len(invitation.to_bytes()) > 16 * 1024:
            raise InvitationError("invitation exceeds size limit")
        return invitation

    def verify(self, trusted_inviter_keys: Mapping[str, bytes], now: datetime | None = None) -> None:
        trusted = trusted_inviter_keys.get(self.inviter_persona_id)
        if trusted is None:
            raise UnknownInviter("inviter is not trusted; verify the fingerprint out of band first")
        try:
            supplied = _unb64(self.inviter_public_key)
            if supplied != trusted:
                raise InvitationError("invitation key does not match trusted inviter key")
            if len(set(self.scopes)) != len(self.scopes) or tuple(sorted(self.scopes)) != self.scopes:
                raise InvitationError("invitation scopes must be unique and sorted")
            if not self.scopes or len(self.scopes) > 32 or any(not s or len(s) > 128 for s in self.scopes):
                raise InvitationError("invitation scopes are invalid")
            if hashlib.sha256(_canonical({k: v for k, v in self.unsigned().items() if k != "invitation_id"})).hexdigest() != self.invitation_id:
                raise InvitationError("invitation ID does not match its contents")
            issued, expires = _timestamp(self.issued_at), _timestamp(self.expires_at)
            current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
            if expires <= issued or expires - issued > timedelta(days=30):
                raise InvitationError("invitation validity interval is invalid")
            if issued > current + timedelta(minutes=5):
                raise InvitationError("invitation issue time is in the future")
            if expires <= current:
                raise InvitationError("invitation has expired")
            Ed25519PublicKey.from_public_bytes(trusted).verify(_unb64(self.signature), _canonical(self.unsigned()))
        except InvalidSignature as exc:
            raise InvitationError("invitation signature is invalid") from exc
        except (ValueError, TypeError) as exc:
            if isinstance(exc, InvitationError):
                raise
            raise InvitationError("invitation encoding is invalid") from exc


class TrustStore:
    def __init__(self, database: Database) -> None:
        self.db = database

    def issue(self, invitation: PeerInvitation) -> None:
        """Persist a locally issued invitation for audit/revocation."""
        encoded = invitation.to_bytes().decode("utf-8")
        with self.db.transaction() as conn:
            conn.execute(
                """INSERT INTO peer_invitations
                   (invitation_id, inviter_persona_id, recipient_persona_id, scopes_json,
                    issued_at, expires_at, invitation_json) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (invitation.invitation_id, invitation.inviter_persona_id,
                 invitation.recipient_persona_id, json.dumps(invitation.scopes),
                 invitation.issued_at, invitation.expires_at, encoded),
            )

    def accept(
        self, invitation: PeerInvitation, *, recipient_persona_id: str,
        trusted_inviter_keys: Mapping[str, bytes], now: datetime | None = None,
    ) -> str:
        """Explicitly accept an invitation and persist its exact scopes."""
        invitation.verify(trusted_inviter_keys, now)
        if not recipient_persona_id:
            raise InvitationError("recipient persona ID is required")
        if invitation.recipient_persona_id not in (None, recipient_persona_id):
            raise InvitationError("invitation is addressed to a different persona")
        accepted_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
        grant_id = str(uuid.uuid4())
        with self.db.transaction() as conn:
            if conn.execute("SELECT 1 FROM invitation_receipts WHERE invitation_id = ?", (invitation.invitation_id,)).fetchone():
                raise InvitationError("invitation has already been used")
            conn.execute(
                "INSERT INTO invitation_receipts(invitation_id, accepted_by_persona_id, accepted_at) VALUES (?, ?, ?)",
                (invitation.invitation_id, recipient_persona_id, accepted_at),
            )
            conn.execute(
                """INSERT INTO peer_trust_grants
                   (grant_id, peer_persona_id, inviter_persona_id, scopes_json,
                    invitation_id, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (grant_id, invitation.inviter_persona_id, invitation.inviter_persona_id,
                 json.dumps(invitation.scopes), invitation.invitation_id, accepted_at, invitation.expires_at),
            )
        return grant_id

    def has_scope(self, peer_persona_id: str, scope: str, now: datetime | None = None) -> bool:
        current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
        rows = self.db.connection.execute(
            """SELECT scopes_json FROM peer_trust_grants
               WHERE peer_persona_id = ? AND revoked_at IS NULL AND expires_at > ?""",
            (peer_persona_id, current),
        ).fetchall()
        return any(scope in json.loads(row["scopes_json"]) for row in rows)

    def revoke(self, grant_id: str, now: datetime | None = None) -> bool:
        revoked_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
        with self.db.transaction() as conn:
            result = conn.execute(
                "UPDATE peer_trust_grants SET revoked_at = ? WHERE grant_id = ? AND revoked_at IS NULL",
                (revoked_at, grant_id),
            )
            return result.rowcount == 1
