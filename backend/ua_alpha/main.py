"""Local development API for the alpha domain.

This API is intentionally bound to localhost in the documented run command.
Authentication is not implemented yet; do not expose this development API to a
network or deploy it publicly until passkey sessions and authorization are added.
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .database import Database
from .domain import DomainError, UrbanAlchemy

DB_PATH = os.getenv("UA_DATABASE_PATH", "./urban-alchemy.sqlite3")
database: Database | None = None
ua: UrbanAlchemy | None = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global database, ua
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    database = Database(DB_PATH)
    ua = UrbanAlchemy(database)
    yield
    database.close()

app = FastAPI(title="Urban Alchemy Alpha API", version="0.1.0", lifespan=lifespan)

class PlayerCreate(BaseModel):
    username: str = Field(min_length=1, max_length=32)

class QuestCreate(BaseModel):
    creator_id: str

class PublishQuest(BaseModel):
    creator_id: str
    title: str = Field(min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=2000)
    instructions: str = Field(min_length=1, max_length=4000)
    category: str = Field(min_length=1, max_length=50)
    location: str | None = Field(default=None, max_length=200)

class AcceptQuest(BaseModel):
    player_id: str
    version_id: str | None = None

class CompleteQuest(BaseModel):
    player_id: str
    evidence: str | None = Field(default=None, max_length=4000)

def service() -> UrbanAlchemy:
    if ua is None:
        raise HTTPException(status_code=503, detail="Database is starting")
    return ua

def call_domain(fn, *args, **kwargs):
    try:
        return fn(*args, **kwargs)
    except DomainError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

@app.get("/health")
def health():
    return {"status": "ok", "service": "urban-alchemy-alpha", "auth": "not-yet-implemented"}

@app.post("/players", status_code=201)
def create_player(body: PlayerCreate):
    player_id = call_domain(service().create_player, body.username)
    return {"player_id": player_id, "username": body.username.strip()}

@app.post("/quests", status_code=201)
def create_quest(body: QuestCreate):
    quest_id = call_domain(service().create_quest, body.creator_id)
    return {"quest_id": quest_id}

@app.post("/quests/{quest_id}/versions", status_code=201)
def publish_quest(quest_id: str, body: PublishQuest):
    version_id = call_domain(
        service().publish_version, quest_id, body.creator_id,
        title=body.title, description=body.description, instructions=body.instructions,
        category=body.category, location=body.location,
    )
    return {"version_id": version_id, "participant_reward": 10, "creator_royalty": 5}

@app.post("/quests/{quest_id}/accept", status_code=201)
def accept_quest(quest_id: str, body: AcceptQuest):
    attempt_id = call_domain(service().accept_quest, quest_id, body.player_id, body.version_id)
    return {"attempt_id": attempt_id, "status": "accepted"}

@app.post("/attempts/{attempt_id}/complete")
def complete_quest(attempt_id: str, body: CompleteQuest):
    result = call_domain(service().complete_quest, attempt_id, body.player_id, body.evidence)
    return {
        "attempt_id": result.attempt_id, "status": "completed",
        "participant_reward": result.participant_reward,
        "creator_royalty": result.creator_royalty,
        "already_completed": result.already_completed,
    }

@app.get("/players/{player_id}/account")
def account(player_id: str):
    ledger = service().ledger(player_id)
    return {"player_id": player_id, "balance": service().balance(player_id), "ledger": ledger}
