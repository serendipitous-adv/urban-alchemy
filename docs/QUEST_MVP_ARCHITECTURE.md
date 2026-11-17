# Urban Alchemy Quest MVP — Architecture & Implementation Brief

Status: implementation brief for the first collaborative quest slice  
Tracking issue: [#54 — Quest MVP inside Android Messenger](https://github.com/serendipitous-adv/urban-alchemy/issues/54)  
Base: stacked on `ux/contact-first-trust-labels` (PR #53); do not retarget to `main` until the messenger MVP chain is deliberately integrated.

## 1. Product intent

The messenger is the familiar door into Urban Alchemy. Quests are the first native mechanism that turns a conversation into shared action.

The MVP should let a person create a small, concrete activity, invite existing contacts, collaborate on objectives, and preserve a completion record. The interface should feel like a natural extension of Messenger, not a separate project-management application.

The initial field test uses three participants, but the data model must not hard-code a party size of three.

## 2. MVP user journey

1. The user opens a **Quests** destination from Messenger home.
2. They see **My Quests**, **Invitations**, and **Completed**.
3. They create a quest with a title, short description, and one or more objectives.
4. They invite one or more saved contacts. The invite is sent through the existing one-to-one LXMF conversation/transport.
5. The invitee sees a structured invitation and chooses **Accept** or **Decline**.
6. Accepted participants see the party roster and objective list.
7. Each participant can mark their own objective progress/completion. This is self-attested; the app must not imply physical-world verification.
8. The quest creator receives participant updates and distributes an updated quest snapshot to the party.
9. Completion is reached only when the configured policy is satisfied. For the first slice, use **all accepted participants self-attest complete**, then have the creator issue the final completion event.
10. The completed quest remains visible in local history.

The app must remain useful offline. Quest records are local-first; outbound invitations and updates queue until the existing LXMF transport can submit them.

## 3. Architectural boundaries

- Keep the existing Kotlin + Jetpack Compose Android application.
- Keep SQLite as the local persistence layer; use additive migrations only.
- Reuse the existing Reticulum/LXMF runtime, contact book, encrypted outbox, and honest delivery states.
- Do not introduce a backend, account service, new radio transport, or parallel cryptographic identity.
- Do not break existing conversations, contacts, app identity, Reticulum identity, or stored messages.
- Keep quest domain logic separate from Compose UI and from transport details.
- Use a typed application payload for quest events. Do not make UI behavior depend on parsing arbitrary user-authored chat prose.
- A dedicated multi-party chat room is out of scope. Quest collaboration is represented by structured quest state plus existing one-to-one contact conversations.

Suggested code boundaries (adapt to existing conventions after inspecting the branch):
- `QuestModels.kt`: domain models, enums, validation.
- `QuestProtocol.kt`: versioned JSON serialization/deserialization and size limits.
- `QuestStore.kt` or additive methods in a focused store: persistence and migrations.
- `QuestRepository.kt`: local state transitions and outbound/inbound event coordination.
- `QuestScreens.kt`: list, create, detail, invitation and progress UI.
- Small, explicit integration points in `MainActivity.kt`; avoid turning it into a second domain layer.

## 4. Minimal domain model

### Quest
- `questId`: random UUID.
- `title`: required, trimmed, bounded (suggest 80 chars).
- `description`: optional, bounded (suggest 1,000 chars).
- `creatorPlayerId`: local UA Player ID of the creator.
- `creatorDestination`: creator's LXMF destination.
- `status`: `DRAFT`, `OPEN`, `ACTIVE`, `COMPLETED`, `CANCELLED`.
- `createdAt`, `updatedAt`, optional `completedAt`.
- `completionPolicy`: v1 fixed to all accepted participants self-attest complete, followed by creator finalization.

### Quest objective
- `objectiveId`: UUID.
- `questId`.
- `position`.
- `title`: required, bounded (suggest 120 chars).
- `description`: optional, bounded (suggest 500 chars).
- Objective completion is recorded per participant, not as a single shared checkbox.

### Quest participant
- `questId`, `playerId`, `lxmfDestination`.
- `displayName`: presentation only; never identity proof.
- `role`: `CREATOR` or `PARTICIPANT`.
- `status`: `INVITED`, `ACCEPTED`, `DECLINED`.
- The creator is implicitly accepted.
- Only a locally verified contact may be invited in v1.

### Quest event
- `eventId`: UUID used for deduplication.
- `questId`, `actorDestination`, optional `actorPlayerId`.
- `type`: `INVITE`, `ACCEPT`, `DECLINE`, `OBJECTIVE_COMPLETED`, `QUEST_SNAPSHOT`, `QUEST_COMPLETED`, `QUEST_CANCELLED`.
- `createdAt`, protocol version, bounded JSON payload.
- Preserve source destination and transport signature-validation result from the LXMF bridge. Do not trust a payload's claimed actor identity without matching it to the authenticated LXMF source and a saved verified contact / known quest participant.

## 5. Authority and synchronization model (v1)

Use a deliberately simple **creator-authoritative** model for the first release. This is easier to reason about than attempting a fully distributed conflict-free data model before group messaging and multi-device identity delegation exist.

- The creator owns the canonical party roster and distributes canonical quest snapshots.
- An invitee can accept or decline only their own invitation.
- A participant can submit progress only for their own participant record.
- The creator validates incoming events, updates the canonical quest state, and sends a new snapshot to each accepted participant.
- Recipients accept snapshots only from the quest creator's verified LXMF destination.
- Recipients accept participant-originated events only when the authenticated source destination matches that participant's stored destination.
- Unknown senders, mismatched destinations, unsupported versions, duplicate event IDs, oversized payloads, and invalid transitions are rejected.
- If two updates conflict, retain the event provenance and let the creator's canonical snapshot resolve the v1 state. Do not silently merge conflicting authority.
- A quest's completion is not inferred from an invite being delivered or from a local participant tapping a button. Each participant self-attests; the creator issues the final completion event only after all accepted participants are complete.

This is an MVP trust model, not a claim of Sybil resistance or proof that real-world objectives occurred. The UI must label progress as participant-reported.

## 6. Wire format

Use a small, versioned JSON envelope carried as a typed LXMF application message. Suggested shape:

```json
{
  "protocol": "ua.quest",
  "version": 1,
  "event_id": "uuid",
  "quest_id": "uuid",
  "type": "INVITE",
  "created_at": 0,
  "payload": {}
}
```

Requirements:
- Strict allowlist of event types and required fields.
- Explicit maximum serialized payload size (suggest 8 KiB for v1).
- Reject malformed JSON, missing fields, invalid UUIDs, unknown versions/types, excessive string lengths, invalid timestamps, and invalid state transitions.
- Deduplicate by `event_id`; persist before acknowledging/consuming the bridge event.
- Bind sender authority to the LXMF source destination provided by the validated transport callback, not a source field inside the JSON.
- Keep application payloads separate from normal chat text. Prefer a transport bridge method that accepts a typed payload or an explicit message-kind field. If the current LXMF bridge only exposes text, define a reserved, unambiguous envelope prefix and parse it before rendering; never render raw protocol JSON as ordinary prose.
- Do not put private keys, secrets, or local-only information in the payload.

## 7. Persistence and migration

Use additive SQLite schema changes. Suggested tables:

- `quests`: canonical local quest record and status.
- `quest_objectives`: ordered objectives.
- `quest_participants`: roster, participant state, and verified destination binding.
- `quest_events`: append-only event ledger with unique `event_id`, actor source, type, timestamp, and serialized payload.
- `quest_objective_progress`: objective completion per participant, including self-attested timestamp and originating event ID.

Migration rules:
- Increment the existing `LocalStore` database version.
- Never drop or recreate contacts, conversations, messages, identity tables, or existing indexes.
- Test migration from the current schema version (7) with representative existing rows.
- Ensure foreign keys / indexes support quest lookup, event deduplication, and participant/objective queries.
- Use transactions for state changes that touch multiple quest tables.

## 8. UI requirements

### Quest home
- Three simple sections or filters: Active, Invitations, Completed.
- Empty state explains that quests are shared real-world activities, not just chat tasks.
- A clear **Create quest** action.
- No player-count labels or role-setup wizard.

### Create quest
- Title, description, objective list.
- Add/remove/reorder objectives.
- Save draft locally.
- Invite saved contacts after saving.

### Quest detail
- Quest title and description.
- Party roster and honest participant statuses.
- Objectives with per-participant progress.
- Invite additional saved contacts while open.
- Show pending/offline delivery state accurately.
- Clear distinction between participant-reported completion and creator-finalized completion.

### Invitation
- Show who invited the user using the saved contact's trust state.
- Accept / Decline controls.
- Never auto-accept an invitation merely because its message arrived.

### Navigation
- Add a visible **Quests** entry to the existing Messenger home.
- Preserve the current contact-first messaging path and all current transport/identity controls.
- Do not replace the messenger home with a quest-first onboarding flow.

## 9. Tests and acceptance

Unit tests:
- Model validation and string limits.
- Protocol round-trip and invalid payload rejection.
- Event deduplication.
- Participant authorization (cannot accept another person's invite or report another person's progress).
- Creator-only snapshot/finalization authority.
- State transition and completion-policy rules.
- SQLite create/read/update and migration from v7 with old messages/contacts/conversations preserved.
- Offline queue state does not claim delivery.

Android validation:
- Debug build and existing Python bridge tests pass.
- Create a local quest, add objectives, save, reopen, and verify persistence.
- Invite at least two contacts.
- Accept/decline from a second device.
- Exchange progress and canonical snapshots.
- Complete with three participants.
- Test duplicate, malformed, oversized, unsupported-version and unauthorized events.
- Interrupt connectivity and verify queued events recover without duplicate application.
- Record that real two-device acceptance remains unverified until performed on physical devices.

## 10. Explicit non-goals

- SCAF rewards, balances, creator royalties, or transferability.
- Guilds, Patch governance, public feeds, global quest discovery, or marketplace.
- Wi-Fi mesh, RF gateway management, or internet-sharing controls.
- Full group chat or arbitrary group messaging.
- Background delivery guarantees beyond the current app-process-bound LXMF runtime.
- Physical-world verification, GPS tracking, surveillance, or proof-of-presence.

## 11. Implementation sequence

1. Add domain models, protocol codec, and tests.
2. Add additive SQLite migration and repository state transitions.
3. Add quest list/create/detail screens with local-only quests.
4. Add typed LXMF invite, accept/decline, progress, snapshot, and completion handling.
5. Add end-to-end tests and Android build validation.
6. Field-test with three actual participants before declaring the quest MVP complete.

## 12. Agent handoff

**Implementation owner (Claude):** implement the above in focused commits/PRs. Begin with models, protocol codec, and tests; do not modify the existing transport or identity model without proposing an architecture change first.

**Architecture / integration (ChatGPT):** review the design and implementation, inspect diffs, enforce acceptance criteria, coordinate CI and integration.

**Product owner (founder):** approve experience decisions and test on-device. No feature should be merged merely because CI compiles.

If Claude is not directly connected to the repository session, provide it this document and issue #54 as its complete handoff. Do not claim a Claude task has been dispatched unless an actual integration confirms it.
