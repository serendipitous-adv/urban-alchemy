"""Quest publication, participation, and append-only SCAF accounting."""
from __future__ import annotations

import sqlite3
import uuid
from dataclasses import dataclass
from typing import Any

from .database import Database

PARTICIPANT_REWARD = 10
CREATOR_ROYALTY = 5

class DomainError(Exception):
    """A rejected domain operation."""

@dataclass(frozen=True)
class CompletionResult:
    attempt_id: str
    participant_reward: int
    creator_royalty: int
    already_completed: bool = False

class UrbanAlchemy:
    def __init__(self, database: Database) -> None:
        self.db = database

    @staticmethod
    def _id() -> str:
        return str(uuid.uuid4())

    def create_player(self, username: str, player_id: str | None = None) -> str:
        username = username.strip()
        if not username or len(username) > 32:
            raise DomainError("Username must contain 1–32 characters.")
        player_id = player_id or self._id()
        try:
            with self.db.transaction() as conn:
                conn.execute("INSERT INTO players(id, username) VALUES (?, ?)", (player_id, username))
        except sqlite3.IntegrityError as exc:
            raise DomainError("Username or player ID already exists.") from exc
        return player_id

    def create_quest(self, creator_id: str) -> str:
        quest_id = self._id()
        with self.db.transaction() as conn:
            self._require_active_player(conn, creator_id)
            conn.execute("INSERT INTO quests(id, creator_id) VALUES (?, ?)", (quest_id, creator_id))
        return quest_id

    def publish_version(
        self, quest_id: str, creator_id: str, *, title: str, description: str,
        instructions: str, category: str, location: str | None = None,
    ) -> str:
        fields = [title.strip(), description.strip(), instructions.strip(), category.strip()]
        if not all(fields):
            raise DomainError("Title, description, instructions, and category are required.")
        version_id = self._id()
        with self.db.transaction() as conn:
            self._require_active_player(conn, creator_id)
            quest = conn.execute("SELECT creator_id FROM quests WHERE id = ?", (quest_id,)).fetchone()
            if quest is None or quest["creator_id"] != creator_id:
                raise DomainError("Quest not found or creator mismatch.")
            version = conn.execute(
                "SELECT COALESCE(MAX(version), 0) + 1 AS next_version FROM quest_versions WHERE quest_id = ?",
                (quest_id,),
            ).fetchone()["next_version"]
            conn.execute(
                """INSERT INTO quest_versions
                (id, quest_id, version, title, description, instructions, category, location,
                 participant_reward, creator_reward)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (version_id, quest_id, version, *fields, location, PARTICIPANT_REWARD, CREATOR_ROYALTY),
            )
            conn.execute("UPDATE quests SET published = 1 WHERE id = ?", (quest_id,))
        return version_id

    def accept_quest(self, quest_id: str, player_id: str, version_id: str | None = None) -> str:
        attempt_id = self._id()
        with self.db.transaction() as conn:
            self._require_active_player(conn, player_id)
            quest = conn.execute("SELECT creator_id, published FROM quests WHERE id = ?", (quest_id,)).fetchone()
            if quest is None or not quest["published"]:
                raise DomainError("Quest is not available.")
            if quest["creator_id"] == player_id:
                raise DomainError("Creators cannot accept their own quests.")
            if version_id is None:
                version = conn.execute(
                    "SELECT id FROM quest_versions WHERE quest_id = ? ORDER BY version DESC LIMIT 1", (quest_id,)
                ).fetchone()
                version_id = version["id"]
            else:
                version = conn.execute(
                    "SELECT id FROM quest_versions WHERE quest_id = ? AND id = ?", (quest_id, version_id)
                ).fetchone()
                if version is None:
                    raise DomainError("Requested quest version does not exist.")
            conn.execute(
                "INSERT INTO attempts(id, quest_id, version_id, player_id, status) VALUES (?, ?, ?, ?, 'accepted')",
                (attempt_id, quest_id, version_id, player_id),
            )
        return attempt_id

    def complete_quest(self, attempt_id: str, player_id: str, evidence: str | None = None) -> CompletionResult:
        with self.db.transaction() as conn:
            attempt = conn.execute(
                """SELECT a.*, q.creator_id, v.participant_reward, v.creator_reward
                   FROM attempts a JOIN quests q ON q.id = a.quest_id
                   JOIN quest_versions v ON v.id = a.version_id AND v.quest_id = a.quest_id
                   WHERE a.id = ? AND a.player_id = ?""",
                (attempt_id, player_id),
            ).fetchone()
            if attempt is None:
                raise DomainError("Attempt not found.")
            self._require_active_player(conn, player_id)
            if attempt["status"] == "completed":
                return CompletionResult(attempt_id, attempt["participant_reward"], attempt["creator_reward"], True)
            conn.execute(
                "UPDATE attempts SET status = 'completed', evidence = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?",
                (evidence, attempt_id),
            )
            conn.execute(
                """INSERT INTO ledger_entries
                (id, player_id, quest_id, attempt_id, entry_type, amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'participant_reward', ?, ?)""",
                (self._id(), player_id, attempt["quest_id"], attempt_id,
                 attempt["participant_reward"], f"participant:{attempt_id}"),
            )
            conn.execute(
                """INSERT INTO ledger_entries
                (id, player_id, quest_id, attempt_id, entry_type, amount, idempotency_key)
                VALUES (?, ?, ?, ?, 'creator_royalty', ?, ?)""",
                (self._id(), attempt["creator_id"], attempt["quest_id"], attempt_id,
                 attempt["creator_reward"], f"creator:{attempt_id}"),
            )
        return CompletionResult(attempt_id, PARTICIPANT_REWARD, CREATOR_ROYALTY)

    def balance(self, player_id: str) -> int:
        row = self.db.connection.execute(
            "SELECT COALESCE(SUM(amount), 0) AS balance FROM ledger_entries WHERE player_id = ?", (player_id,)
        ).fetchone()
        return int(row["balance"])

    def ledger(self, player_id: str) -> list[dict[str, Any]]:
        rows = self.db.connection.execute(
            "SELECT * FROM ledger_entries WHERE player_id = ? ORDER BY created_at, id", (player_id,)
        ).fetchall()
        return [dict(row) for row in rows]

    @staticmethod
    def _require_active_player(conn: sqlite3.Connection, player_id: str) -> None:
        player = conn.execute("SELECT suspended FROM players WHERE id = ?", (player_id,)).fetchone()
        if player is None:
            raise DomainError("Player not found.")
        if player["suspended"]:
            raise DomainError("Player account is suspended.")
