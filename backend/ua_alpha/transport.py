"""Transport contracts shared by Reticulum and internet adapters.

Adapters authenticate remote endpoints and deliver bounded protocol messages.
They do not decide event trust or domain authority.
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Protocol, runtime_checkable

from .sync import SyncBatch


class TransportError(RuntimeError):
    """A transport could not establish or maintain a peer exchange."""


@dataclass(frozen=True)
class PeerAddress:
    transport: str
    address: str

    def __post_init__(self) -> None:
        if self.transport not in {"reticulum", "internet", "offline"}:
            raise ValueError("unsupported transport")
        if not self.address or len(self.address) > 1024:
            raise ValueError("address must contain 1–1024 characters")


@dataclass(frozen=True)
class PeerSession:
    """Authenticated local/remote peer identities supplied by the adapter."""

    peer_id: str
    transport: str
    authenticated: bool
    session_id: str
    local_peer_id: str

    def require_authenticated(self) -> None:
        if not self.authenticated:
            raise TransportError("peer session is not authenticated")
        if not self.peer_id or not self.local_peer_id or not self.session_id:
            raise TransportError("peer session identity is incomplete")
        if self.peer_id == self.local_peer_id:
            raise TransportError("a peer session cannot be self-connected")


@dataclass(frozen=True)
class SyncAcknowledgement:
    peer_id: str
    cursor: str | None


@runtime_checkable
class PeerTransport(Protocol):
    @property
    def name(self) -> str: ...

    def exchange(self, address: PeerAddress, batch: SyncBatch) -> SyncAcknowledgement:
        """Send one bounded batch and return a durable-receipt acknowledgement."""
        ...


class SyncCoordinator:
    """Connects authenticated transport sessions to the shared sync engine."""

    def __init__(self, engine, trusted_personas) -> None:
        self.engine = engine
        self.trusted_personas = trusted_personas

    def send_once(self, session: PeerSession, transport: PeerTransport, address: PeerAddress, limit: int = 100) -> int:
        session.require_authenticated()
        if session.transport != transport.name or address.transport != transport.name:
            raise TransportError("session, transport, and address must use the same transport")
        batch = self.engine.prepare_outbound(session.peer_id, limit)
        if not batch.events:
            return 0
        # The batch identifies its sender; the durable send cursor is keyed by
        # the remote peer. These are intentionally different identities.
        batch = replace(batch, peer_id=session.local_peer_id)
        acknowledgement = transport.exchange(address, batch)
        if acknowledgement.peer_id != session.peer_id:
            raise TransportError("acknowledgement peer does not match authenticated session")
        if acknowledgement.cursor != batch.cursor:
            raise TransportError("acknowledgement cursor does not match sent batch")
        self.engine.acknowledge_outbound(session.peer_id, acknowledgement.cursor)
        return len(batch.events)

    def receive_once(self, session: PeerSession, batch: SyncBatch) -> int:
        session.require_authenticated()
        return self.engine.receive_batch(session.peer_id, batch, self.trusted_personas)
