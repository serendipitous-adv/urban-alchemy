# Urban Alchemy Alpha — backend foundation

This is the first implementation slice: a local FastAPI API, SQLite persistence, immutable quest-version records, participation attempts, and transactional SCAF ledger posting.

## Run locally

Requires Python 3.12 or newer.

```bash
cd backend
python -m venv .venv
# Windows PowerShell: .venv\\Scripts\\Activate.ps1
# macOS/Linux: source .venv/bin/activate
python -m pip install -e '.[test]'
python -m uvicorn ua_alpha.main:app --host 127.0.0.1 --port 8000
```

Open http://127.0.0.1:8000/docs for the development API and http://127.0.0.1:8000/health for its health check.

Run the domain tests:

```bash
cd backend
python -m pytest
```

The SQLite file defaults to `backend/urban-alchemy.sqlite3` when started from this directory. Override it with `UA_DATABASE_PATH`.

## Important security status

**Development only.** Authentication, passkeys, sessions, CSRF protection, moderation, and authorization scopes are not implemented yet. The API has intentionally been documented to bind to localhost. Do not expose it to a LAN, the public internet, or a production deployment. Player IDs are currently accepted as development identifiers, not proof of identity.

## Domain invariants in this slice

- SCAF is integer-only and internal: 10 to an eligible participant and 5 to the quest creator.
- Quest identity is stable; each publication creates a separate version row.
- An accepted attempt references the exact version it accepted.
- A player can accept a given quest once, regardless of later versions.
- Creator self-acceptance is rejected.
- Completion state and both ledger entries are written in one SQLite transaction.
- Completion retries return the existing result without issuing duplicate ledger entries.
- Balances are calculated from ledger entries, not stored as mutable account state.

The current schema and service layer are a foundation, not yet the full alpha. Reticulum transport, passkey authentication, frontend, moderation, migrations, and multi-node synchronization remain subsequent slices.
