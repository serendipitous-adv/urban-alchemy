"""SQLite persistence for the first Urban Alchemy alpha slice."""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS players (
    id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    suspended INTEGER NOT NULL DEFAULT 0 CHECK (suspended IN (0, 1))
);
CREATE TABLE IF NOT EXISTS quests (
    id TEXT PRIMARY KEY, creator_id TEXT NOT NULL REFERENCES players(id),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published INTEGER NOT NULL DEFAULT 0 CHECK (published IN (0, 1))
);
CREATE TABLE IF NOT EXISTS quest_versions (
    id TEXT PRIMARY KEY, quest_id TEXT NOT NULL REFERENCES quests(id),
    version INTEGER NOT NULL CHECK (version > 0), title TEXT NOT NULL,
    description TEXT NOT NULL, instructions TEXT NOT NULL, category TEXT NOT NULL,
    location TEXT, participant_reward INTEGER NOT NULL CHECK (participant_reward = 10),
    creator_reward INTEGER NOT NULL CHECK (creator_reward = 5),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (quest_id, version), UNIQUE (quest_id, id)
);
CREATE TABLE IF NOT EXISTS attempts (
    id TEXT PRIMARY KEY, quest_id TEXT NOT NULL REFERENCES quests(id),
    version_id TEXT NOT NULL, player_id TEXT NOT NULL REFERENCES players(id),
    status TEXT NOT NULL CHECK (status IN ('accepted', 'completed')),
    evidence TEXT, accepted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at TEXT,
    FOREIGN KEY (quest_id, version_id) REFERENCES quest_versions(quest_id, id),
    UNIQUE (quest_id, player_id)
);
CREATE TABLE IF NOT EXISTS ledger_entries (
    id TEXT PRIMARY KEY, player_id TEXT NOT NULL REFERENCES players(id),
    quest_id TEXT NOT NULL REFERENCES quests(id), attempt_id TEXT NOT NULL REFERENCES attempts(id),
    entry_type TEXT NOT NULL CHECK (entry_type IN ('participant_reward', 'creator_royalty')),
    amount INTEGER NOT NULL CHECK (amount > 0), idempotency_key TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (attempt_id, entry_type)
);
CREATE TRIGGER IF NOT EXISTS quest_versions_no_update BEFORE UPDATE ON quest_versions BEGIN
    SELECT RAISE(ABORT, 'published quest versions are immutable');
END;
CREATE TRIGGER IF NOT EXISTS quest_versions_no_delete BEFORE DELETE ON quest_versions BEGIN
    SELECT RAISE(ABORT, 'published quest versions cannot be deleted');
END;
CREATE TRIGGER IF NOT EXISTS ledger_no_update BEFORE UPDATE ON ledger_entries BEGIN
    SELECT RAISE(ABORT, 'ledger entries are append-only');
END;
CREATE TRIGGER IF NOT EXISTS ledger_no_delete BEFORE DELETE ON ledger_entries BEGIN
    SELECT RAISE(ABORT, 'ledger entries are append-only');
END;
CREATE INDEX IF NOT EXISTS idx_versions_quest ON quest_versions(quest_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_attempts_player ON attempts(player_id, status);
CREATE INDEX IF NOT EXISTS idx_ledger_player ON ledger_entries(player_id, created_at);
CREATE TABLE IF NOT EXISTS event_records (
    event_id TEXT PRIMARY KEY, persona_id TEXT NOT NULL, event_type TEXT NOT NULL,
    created_at TEXT NOT NULL, event_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_order ON event_records(created_at, event_id);
CREATE INDEX IF NOT EXISTS idx_events_persona ON event_records(persona_id, created_at);
CREATE TABLE IF NOT EXISTS peer_sync_state (
    peer_id TEXT NOT NULL, direction TEXT NOT NULL CHECK (direction IN ('send', 'receive')),
    cursor_event_id TEXT, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (peer_id, direction)
);
CREATE TRIGGER IF NOT EXISTS event_records_no_update BEFORE UPDATE ON event_records BEGIN
    SELECT RAISE(ABORT, 'event records are append-only');
END;
CREATE TRIGGER IF NOT EXISTS event_records_no_delete BEFORE DELETE ON event_records BEGIN
    SELECT RAISE(ABORT, 'event records are append-only');
END;
CREATE TABLE IF NOT EXISTS peer_invitations (
    invitation_id TEXT PRIMARY KEY, inviter_persona_id TEXT NOT NULL,
    recipient_persona_id TEXT, scopes_json TEXT NOT NULL, issued_at TEXT NOT NULL,
    expires_at TEXT NOT NULL, invitation_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS invitation_receipts (
    invitation_id TEXT PRIMARY KEY, accepted_by_persona_id TEXT NOT NULL,
    accepted_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS peer_trust_grants (
    grant_id TEXT PRIMARY KEY, peer_persona_id TEXT NOT NULL,
    inviter_persona_id TEXT NOT NULL, scopes_json TEXT NOT NULL,
    invitation_id TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL, revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_trust_peer ON peer_trust_grants(peer_persona_id, revoked_at);
"""

class Database:
    def __init__(self, path: str | Path = ":memory:") -> None:
        self.path = str(path)
        self.connection = sqlite3.connect(self.path, isolation_level=None, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.connection.executescript(SCHEMA)

    @contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            yield self.connection
        except Exception:
            self.connection.rollback()
            raise
        else:
            self.connection.commit()

    def close(self) -> None:
        self.connection.close()
