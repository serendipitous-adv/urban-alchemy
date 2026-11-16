"""Transport contracts shared by Reticulum and internet adapters.

Adapters are responsible for authenticating the remote endpoint and delivering
bounded protocol messages. They do not decide event trust or domain authority.
"""
from __future__ import annotations

from dataclasses import dataclass
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
    """An authenticated transport session; identity is supplied by its adapter."""

    peer_id: str
    transport: str
    authenticated: bool
    session_id: str

    def require_authenticated(self) -> None:
        if not self.authenticated:
            raise TransportError("peer session is not authenticated")
        if not self.peer_id or not self.session_id:
            raise TransportError("peer session identity is incomplete")


@dataclass(frozen=True)
class SyncAcknowledgement:
    peer_id: str
    cursor: str | None


@runtime_checkable
class PeerTransport(Protocol):
    """Minimal transport contract; concrete implementations own framing/security."""

    @property
    def name(self) -> str: ...

    def exchange(self, address: PeerAddress, batch: SyncBatch) -> SyncAcknowledgement:
        """Send one bounded batch and return a durable-receipt acknowledgement."""
        ...


class SyncCoordinator:
    """Connects an authenticated transport session to the shared sync engine."""

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
