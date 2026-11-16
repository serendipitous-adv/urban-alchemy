"""Transport-neutral, resumable peer synchronization over signed events.

This module deliberately does not open sockets or know about Reticulum, HTTP,
or any relay. Adapters exchange SyncBatch objects and call the same methods.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Mapping

from .database import Database
from .events import EventError, EventStore, SignedEvent

MAX_SYNC_EVENTS = 100
MAX_SYNC_BYTES = 512 * 1024


class SyncError(ValueError):
    """A peer sync request is invalid or would move durable state backwards."""


@dataclass(frozen=True)
class SyncBatch:
    peer_id: str
    events: tuple[SignedEvent, ...]
    cursor: str | None

    def to_dict(self) -> dict:
        return {
            "protocol": "ua.sync.batch.v1",
            "peer_id": self.peer_id,
            "events": [event.to_dict() for event in self.events],
            "cursor": self.cursor,
        }


class SyncEngine:
    """Durable per-peer cursors, idempotent receipt, and explicit acknowledgements."""

    def __init__(self, database: Database, event_store: EventStore | None = None) -> None:
        self.db = database
        self.events = event_store or EventStore(database)

    def prepare_outbound(self, peer_id: str, limit: int = 100) -> SyncBatch:
        self._validate_peer(peer_id)
        if not 1 <= limit <= MAX_SYNC_EVENTS:
            raise SyncError(f"limit must be between 1 and {MAX_SYNC_EVENTS}")
        row = self.db.connection.execute(
            "SELECT cursor_event_id FROM peer_sync_state WHERE peer_id = ? AND direction = 'send'",
            (peer_id,),
        ).fetchone()
        after = row["cursor_event_id"] if row else None
        events = self.events.export_after(after, limit)
        total = sum(len(event.to_bytes()) for event in events)
        while events and total > MAX_SYNC_BYTES:
            events = events[:-1]
            total = sum(len(event.to_bytes()) for event in events)
        cursor = events[-1].event_id if events else None
        return SyncBatch(peer_id=peer_id, events=tuple(events), cursor=cursor)

    def acknowledge_outbound(self, peer_id: str, cursor: str) -> None:
        """Advance send cursor only after the remote peer confirms durable receipt."""
        self._validate_peer(peer_id)
        event = self.events.get(cursor)
        if event is None:
            raise SyncError("acknowledgement cursor is not a locally stored event")
        self._advance_cursor(peer_id, "send", cursor, event.created_at)

    def receive_batch(
        self, peer_id: str, batch: SyncBatch, trusted_personas: Mapping[str, bytes]
    ) -> int:
        """Atomically persist verified events and the received cursor."""
        self._validate_peer(peer_id)
        if batch.peer_id != peer_id:
            raise SyncError("batch peer identity does not match authenticated peer")
        if len(batch.events) > MAX_SYNC_EVENTS:
            raise SyncError("batch contains too many events")
        total = sum(len(event.to_bytes()) for event in batch.events)
        if total > MAX_SYNC_BYTES:
            raise SyncError("batch exceeds the transfer size limit")
        if batch.events:
            keys = [(event.created_at, event.event_id) for event in batch.events]
            if keys != sorted(keys) or len({event.event_id for event in batch.events}) != len(batch.events):
                raise SyncError("batch events must be uniquely ordered")
            if batch.cursor != batch.events[-1].event_id:
                raise SyncError("batch cursor must identify its final event")
        elif batch.cursor is not None:
            raise SyncError("empty batch cannot advance a cursor")

        for event in batch.events:
            if not event.verify(trusted_personas):
                raise EventError("batch contains an untrusted or invalid event")

        inserted = 0
        with self.db.transaction() as conn:
            for event in batch.events:
                encoded = event.to_bytes().decode("utf-8")
                existing = conn.execute(
                    "SELECT event_json FROM event_records WHERE event_id = ?", (event.event_id,)
                ).fetchone()
                if existing:
                    if existing["event_json"] != encoded:
                        raise EventError("event ID collision with different content")
                    continue
                conn.execute(
                    """INSERT INTO event_records(event_id, persona_id, event_type, created_at, event_json)
                       VALUES (?, ?, ?, ?, ?)""",
                    (event.event_id, event.persona_id, event.event_type, event.created_at, encoded),
                )
                inserted += 1
            if batch.cursor:
                self._advance_cursor_in_transaction(
                    conn, peer_id, "receive", batch.cursor, batch.events[-1].created_at
                )
        return inserted

    def cursor(self, peer_id: str, direction: str) -> str | None:
        self._validate_peer(peer_id)
        if direction not in {"send", "receive"}:
            raise SyncError("direction must be 'send' or 'receive'")
        row = self.db.connection.execute(
            "SELECT cursor_event_id FROM peer_sync_state WHERE peer_id = ? AND direction = ?",
            (peer_id, direction),
        ).fetchone()
        return row["cursor_event_id"] if row else None

    def _advance_cursor(self, peer_id: str, direction: str, event_id: str, created_at: str) -> None:
        with self.db.transaction() as conn:
            self._advance_cursor_in_transaction(conn, peer_id, direction, event_id, created_at)

    @staticmethod
    def _advance_cursor_in_transaction(conn, peer_id: str, direction: str, event_id: str, created_at: str) -> None:
        current = conn.execute(
            "SELECT cursor_event_id FROM peer_sync_state WHERE peer_id = ? AND direction = ?",
            (peer_id, direction),
        ).fetchone()
        if current and current["cursor_event_id"]:
            old = conn.execute(
                "SELECT created_at, event_id FROM event_records WHERE event_id = ?",
                (current["cursor_event_id"],),
            ).fetchone()
            if old and (created_at, event_id) < (old["created_at"], old["event_id"]):
                raise SyncError("sync cursor cannot move backwards")
        conn.execute(
            """INSERT INTO peer_sync_state(peer_id, direction, cursor_event_id)
               VALUES (?, ?, ?)
               ON CONFLICT(peer_id, direction) DO UPDATE SET
                   cursor_event_id = excluded.cursor_event_id,
                   updated_at = CURRENT_TIMESTAMP""",
            (peer_id, direction, event_id),
        )

    @staticmethod
    def _validate_peer(peer_id: str) -> None:
        if not isinstance(peer_id, str) or not peer_id.strip() or len(peer_id) > 256:
            raise SyncError("peer_id must contain 1–256 characters")
