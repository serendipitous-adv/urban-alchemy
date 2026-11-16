"""SQLite persistence for the first Urban Alchemy alpha slice."""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS players (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    suspended INTEGER NOT NULL DEFAULT 0 CHECK (suspended IN (0, 1))
);
CREATE TABLE IF NOT EXISTS quests (
    id TEXT PRIMARY KEY,
    creator_id TEXT NOT NULL REFERENCES players(id),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published INTEGER NOT NULL DEFAULT 0 CHECK (published IN (0, 1))
);
CREATE TABLE IF NOT EXISTS quest_versions (
    id TEXT PRIMARY KEY,
    quest_id TEXT NOT NULL REFERENCES quests(id),
    version INTEGER NOT NULL CHECK (version > 0),
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    instructions TEXT NOT NULL,
    category TEXT NOT NULL,
    location TEXT,
    participant_reward INTEGER NOT NULL CHECK (participant_reward = 10),
    creator_reward INTEGER NOT NULL CHECK (creator_reward = 5),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (quest_id, version),
    UNIQUE (quest_id, id)
);
CREATE TABLE IF NOT EXISTS attempts (
    id TEXT PRIMARY KEY,
    quest_id TEXT NOT NULL REFERENCES quests(id),
    version_id TEXT NOT NULL,
    player_id TEXT NOT NULL REFERENCES players(id),
    status TEXT NOT NULL CHECK (status IN ('accepted', 'completed')),
    evidence TEXT,
    accepted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TEXT,
    FOREIGN KEY (quest_id, version_id) REFERENCES quest_versions(quest_id, id),
    UNIQUE (quest_id, player_id)
);
CREATE TABLE IF NOT EXISTS ledger_entries (
    id TEXT PRIMARY KEY,
    player_id TEXT NOT NULL REFERENCES players(id),
    quest_id TEXT NOT NULL REFERENCES quests(id),
    attempt_id TEXT NOT NULL REFERENCES attempts(id),
    entry_type TEXT NOT NULL CHECK (entry_type IN ('participant_reward', 'creator_royalty')),
    amount INTEGER NOT NULL CHECK (amount > 0),
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (attempt_id, entry_type)
);
CREATE INDEX IF NOT EXISTS idx_versions_quest ON quest_versions(quest_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_attempts_player ON attempts(player_id, status);
CREATE INDEX IF NOT EXISTS idx_ledger_player ON ledger_entries(player_id, created_at);
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
