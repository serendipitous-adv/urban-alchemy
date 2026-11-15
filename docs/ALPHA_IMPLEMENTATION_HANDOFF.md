# Urban Alchemy Alpha — Claude Code Implementation Handoff

**Status:** Approved implementation brief  
**Target:** First runnable, end-to-end web alpha  
**Base branch:** `dev`  
**Working branch:** `feat/alpha-implementation-handoff`  
**Product authority:** Project owner  
**Implementation partner:** Claude Code  
**Independent architecture/review partner:** GPT

This document turns the product owner's approved assignment into an execution brief. It is not a request for another architecture proposal. Inspect the repository, make a short initial report, then implement.

## 1. Immediate instruction

Build a working, independently runnable mobile-first web application proving this complete loop:

1. Player A registers with a passkey and chooses a pseudonymous username.
2. Player A creates and publishes a quest.
3. Player B registers and discovers the quest in the public marketplace.
4. Player B accepts it and completes it through self-attestation, optionally adding text evidence.
5. Player B receives the participant reward.
6. Player A receives the creator royalty.
7. Both ledger entries appear in the correct account histories.
8. Retrying the completion request cannot issue duplicate entries.
9. Player A can publish a new quest version without changing the accepted attempt, prior completion, or historical ledger terms.

**Do not stop after planning.** Once repository inspection is complete, proceed with implementation. Make reasonable choices for unresolved details, document them, and keep moving. Ask the owner only when a genuine blocker or consequential product decision cannot be resolved safely.

## 2. Repository state observed at handoff

At the time this brief was prepared:

- `main` contains only `README.md` and `LICENSE`.
- `dev` contains those files plus `docs/AI_COLLABORATION.md`.
- The existing collaboration protocol says to branch from `dev`, work on a task branch, test changes, and open a PR back to `dev`.
- The branch for this handoff is `feat/alpha-implementation-handoff`.

Re-check the live repository and branch head before editing. Do not assume this snapshot is still current. Preserve any work added after this brief was written. The Discord bot sandbox is reference material only; do not port or repair it.

## 3. Settled product decisions — do not reopen

- Anyone with an account can create quests.
- Players use pseudonymous usernames.
- Authentication is passkeys/WebAuthn; no email or legal identity requirement.
- Quests are publicly discoverable.
- Completion is self-attested; evidence is optional text.
- No GPS, location verification, mandatory photos, or mandatory proof.
- SCAF is an internal integer ledger, not a cryptocurrency.
- No player-to-player transfers.
- Participant rewards and creator royalties are separate ledger transactions.
- Creator royalty entitlement belongs to the quest creator for this alpha; keep authorship and royalty entitlement conceptually separable in the schema for future evolution, but do not implement transferability.
- Published quest versions are immutable.
- Publishing an edit creates a new version.
- An accepted attempt retains its accepted version and economic terms.
- A player may receive rewards only once per quest identity across all versions.
- A creator cannot earn a creator royalty from their own completion.
- Discord integration, guilds, maps, governance, blockchain, and transferability are out of scope.

## 4. Reward economics

Use a backend-configurable standard issuance budget of **15 SCAF per eligible completion**.

Default split:
- Participant: 10 SCAF
- Creator: 5 SCAF

Quest creators may choose the participant allocation from 5 through 13 inclusive, in integer increments. Creator allocation is the remainder, so the sum is always exactly 15. Default to 10/5.

Store the allocation on the immutable published quest version and snapshot the accepted terms onto the attempt (or make the attempt's immutable version reference authoritative). Never trust reward amounts submitted by the client.

Each eligible distinct player completion creates two separate ledger entries in the same database transaction:
- Participant reward: +participant allocation
- Creator royalty: +creator allocation

A completion by the creator may be recorded as completed if the product flow permits, but it must not issue a participant reward or creator royalty. Prefer to prevent self-acceptance in the UI and enforce the rule in the service layer.

A player can be rewarded only once for a quest identity, even if they encounter multiple versions or attempt retries. A creator royalty is generated once for each distinct eligible non-creator player completion. Completion, idempotency, uniqueness, and ledger writes must be transactionally safe.

## 5. Recommended implementation stack

Use this stack unless current repository contents or a concrete compatibility issue provide a compelling reason not to:

### Backend
- Python 3.12+
- FastAPI
- SQLAlchemy 2.x
- SQLite for alpha
- Alembic migrations
- Pytest

### Frontend
- React
- TypeScript
- Vite

### Authentication and security
- WebAuthn/passkeys using a maintained Python WebAuthn library.
- Secure HttpOnly cookie sessions.
- CSRF protection for state-changing cookie-authenticated requests.
- Secure passwordless session lifecycle, origin/RP ID configuration, challenge expiry and one-time use.
- Rate-limit or otherwise protect authentication challenge endpoints where practical.
- Never log credentials, session secrets, or authentication challenges.
- Provide a documented local-development origin configuration. Passkeys require a secure context; localhost is suitable for local development.
- Recovery may be deferred if necessary. Document the limitation clearly; do not make email a surprise requirement.

Keep business logic independent of FastAPI route handlers. Use a simple single-deployment architecture. Avoid microservices, Redis, queues, blockchain, or speculative infrastructure.

## 6. Minimum domain model

Represent at least:

- Player
- PasskeyCredential
- Session
- Quest (stable identity and creator relationship)
- QuestVersion (immutable published content and reward terms)
- Attempt (player, quest identity, accepted version, status, completion/idempotency metadata)
- LedgerEntry (append-only signed integer amount, entry type, player, quest, attempt, timestamp, idempotency/reference key)
- Report
- ModerationAction

You may add small supporting tables as needed. Use foreign keys, unique constraints, indexes, and database transactions to enforce invariants rather than relying only on frontend or route-level validation.

### Important modeling rules

- Separate stable quest identity from version identity.
- A new publication creates a new version row; do not update a published version in place.
- An accepted attempt points to the exact version accepted.
- Historical ledger entries point to the relevant quest and attempt and preserve the amounts actually issued.
- Use integer SCAF amounts only.
- Ledger history is append-only. Corrections use compensating entries; do not edit or delete prior ledger entries.
- Enforce at most one reward-eligible completion per player per quest identity, across versions.
- Ensure retries cannot create a second participant reward or creator royalty.
- The attempt completion state transition and both reward entries must commit or roll back together.
- Balance is the sum of ledger entries for that player; do not store a client-editable balance.
- Add database-level safeguards for duplicate rewards and idempotency wherever SQLite supports them. Explain any invariant that can only be fully enforced in service logic.

Use clear transaction boundaries and test concurrent/retried completion behavior to the extent practical for SQLite.

## 7. Required user experience

### Marketplace
Public list of published quests. Cards show title, short description, category, optional location, participant reward, creator royalty, and creator username. Quest detail shows full description and instructions. Add simple category filtering if it does not delay the core loop.

### Quest builder
Any authenticated player can create a draft and publish it. Fields: title, description, instructions, category, optional location, participant reward allocation. Make the fixed 15-SCAF budget and complementary creator share obvious. Provide a simple path to publish an updated version of an existing quest.

### Participation
Player can accept a quest, see active and completed quests, and complete through explicit self-attestation. Optional text evidence is allowed. The accepted attempt must retain the accepted version and reward terms.

### Account
Show pseudonymous profile, current SCAF balance derived from ledger entries, active quests, completed quests, and transaction history. Clearly distinguish participant rewards from creator royalties and link each transaction to its quest.

### Moderation
Implement the minimum useful set:
- Report a quest.
- Admin view of reported quests.
- Admin can unpublish/remove a quest.
- Admin can suspend an account.
- Record each moderation action with actor, target, action, reason, and timestamp.

Use a simple, documented admin bootstrap mechanism through environment configuration. Do not ship a hardcoded public admin credential or silently grant admin to arbitrary users.

## 8. Visual direction

Use a dark, atmospheric, mobile-first interface:
- Deep purple and charcoal base.
- Gold and warm accent colors.
- Modern, readable typography.
- Attractive quest cards.
- Clear participant and creator reward displays.
- A sense of entering a living world, not corporate task-management software.

Functionality and legibility take priority over decorative polish. Ensure layouts work on narrow mobile browsers and controls are touch-friendly.

## 9. Suggested execution order

Adjust if implementation realities warrant it, but keep the end-to-end loop as the priority.

1. Inspect current branch, tree, files, dependencies, and existing work. Report the actual state and any genuine blocker.
2. Establish backend/frontend skeleton, environment configuration, migrations, health endpoint, and local run instructions.
3. Implement domain models and service-layer quest/version/attempt/ledger logic with focused tests.
4. Implement passkey registration/login, session handling, logout, CSRF, and auth tests.
5. Implement marketplace, quest detail, create/publish/version, accept, complete, and account/ledger UI.
6. Implement moderation essentials.
7. Add seed/demo data, responsive polish, CI, security checks, and complete setup documentation.
8. Run the full test/build checks, fix failures, and report exact outcomes.

Do not spend the whole implementation window on framework scaffolding or visual polish before the quest/reward loop works.

## 10. Seeded demonstration experience

Include a safe, repeatable seed command that creates useful demonstration quests and any clearly labeled demo data needed to explore the marketplace immediately after setup.

Do not create fake passkey credentials or pretend a demo account can authenticate without a real passkey. Explain how a human creates their first account locally. Seed data must be idempotent and must not create duplicate quest versions or ledger transactions when run repeatedly.

## 11. Tests that matter

At minimum, test:

- Participant reward and creator royalty split sums to 15.
- Participant allocation bounds 5–13 and integer-only validation.
- Creator receives no royalty for own completion.
- Player cannot receive rewards twice for the same quest identity across versions.
- Two distinct eligible players completing one quest each generate their own creator royalty.
- Completion retry is idempotent and does not duplicate either ledger entry.
- Attempt retains accepted version and economic terms after a new version is published.
- Published version cannot be mutated through normal application operations.
- Balance is derived from ledger entries.
- Ledger entries cannot be edited/deleted through application services.
- Failure during reward posting rolls back completion and all associated ledger writes.
- Authentication challenge expiry/one-time use and session logout behavior.
- Suspended users cannot perform authenticated quest actions.
- Moderation actions are recorded.

Run backend tests, frontend typecheck/build, and any lint checks configured by the project. Report exact commands and actual pass/fail counts. Never claim an unrun test passed.

## 12. Configuration and documentation

Provide:
- Root README with prerequisites, setup, migration, seed, run, test, and build commands.
- Backend and frontend environment examples containing placeholders only.
- Alembic migration workflow.
- Local WebAuthn origin/RP ID setup notes.
- Admin bootstrap instructions.
- Known limitations, especially account recovery and deployment requirements for passkeys.
- A short architecture note explaining the transaction and ledger invariants.
- CI workflow for backend tests and frontend build/typecheck if practical.

Never commit secrets, tokens, real credentials, local databases, or private user data.

## 13. Git and collaboration discipline

Follow `docs/AI_COLLABORATION.md`:
- Re-check branch state and recent commits before editing.
- Work on a short-lived task branch from `dev`; do not commit routine work directly to `main` or shared `dev`.
- Make small, coherent commits.
- Do not overwrite or revert other work.
- Add tests alongside behavior changes.
- Open a PR targeting `dev` with purpose, implementation summary, acceptance criteria, exact tests run, risks, migrations/configuration, and deferred work.
- Do not merge, deploy, force-push, rewrite history, or change repository settings without owner approval.
- The owner carries GPT review feedback to Claude; the assistants do not communicate directly.

## 14. Definition of done

The alpha is done only when the complete Player A → quest publication → Player B acceptance → self-attested completion → two ledger rewards → retry safety → immutable historical version scenario works on a mobile browser, with repeatable setup instructions and tests demonstrating the accounting safeguards.

If any item cannot be completed, do not disguise it as done. Deliver the strongest runnable vertical slice, clearly list what remains, and identify the shortest next implementation step.

## 15. Required first status report

Before making changes, send the project owner a concise report covering:
1. Actual repository state, current branch, and recent commits.
2. Existing files/configuration relevant to the build.
3. Whether the recommended stack remains appropriate.
4. Any genuine blockers.
5. The first implementation slice you are starting.

Then continue directly into implementation. Do not return another standalone architecture proposal.
