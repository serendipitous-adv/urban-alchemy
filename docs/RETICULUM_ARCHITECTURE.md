# Reticulum Integration: Prototype-First Architecture

**Status:** Working architecture direction; deliberately provisional.  
**Decision style:** Implement the smallest useful slice, test it in real conditions, and revise based on evidence. This document is not a commitment to implement every capability described here before the first live test.

## Product principle

Urban Alchemy (UA) participants should be able to communicate over infrastructure they do not own or operate. Operating infrastructure is an optional contribution, not a prerequisite for participation.

UA should be usable by an ordinary participant device, while individuals, guilds, and federations may voluntarily contribute relays, gateways, propagation services, or other infrastructure.

## Current architectural direction

1. **Reticulum-native transport integration.** Use Reticulum and LXMF for native mesh networking and human messaging. Keep UA service interfaces transport-neutral so conventional internet/LAN transports and future adapters remain possible.
2. **No ownership requirement.** A participant can use reachable third-party Reticulum infrastructure without owning every intermediate transport node. Network access still depends on compatible configuration and a physical/logical path.
3. **End-to-end message confidentiality.** LXMF messages are encrypted for their intended destination. Forwarding nodes should not need message plaintext. This does not hide all metadata or guarantee delivery; nodes can drop, delay, or refuse traffic.
4. **Store-and-forward.** Use LXMF propagation where available for recipients who are temporarily offline. Propagation is opportunistic infrastructure, not a guarantee that a message can cross a disconnected geography.
5. **Selective discovery.** Separate infrastructure discovery from discovery of people, identities, guilds, and social relationships. Do not create a globally searchable social graph by default.
6. **Local choice and federated cooperation.** Users and guilds may maintain local preferences and exchange infrastructure recommendations. Avoid a mandatory global ranking or central directory.
7. **Graceful isolation.** Local guild/community functions should continue where practical during disconnection and synchronize when connectivity returns.

## Roles (not status levels)

- **Participant:** Sends and receives messages; need not operate infrastructure.
- **Infrastructure volunteer:** Chooses to operate a transport node, gateway, or propagation node.
- **Guild/federation operator:** Coordinates infrastructure for a community while remaining interoperable with the wider network.

These roles are voluntary and can overlap. UA must not imply that infrastructure operators automatically gain authority over users, guild governance, or message contents.

## Discovery and trust: defer complexity

For the first prototype, rely on Reticulum's own path and destination mechanisms. Do not build a UA-wide node discovery protocol or reputation score yet.

If real use later demonstrates a need, prototype narrow, explainable signals such as observed reachability, recent delivery success, latency, and operator-provided capabilities. Keep observations local or scoped to consenting communities. Treat measurements as imperfect and potentially manipulated; do not collapse them into a universal trust score.

Infrastructure trust is not identity trust. Trusting a relay to forward packets must not imply trusting its operator as a person, guild, or authority.

## Transport and service boundaries

- **Reticulum:** Network transport and routing.
- **LXMF:** Human-readable message delivery, including direct and propagation delivery.
- **UA messaging service:** Conversation model, contact UX, delivery state, and future binding between UA personas and transport identities.
- **UA synchronization:** Replication of signed UA events and application state. Do not conflate this with LXMF human messaging.

Reticulum identity keys and UA persona keys remain distinct until an explicit, verifiable binding protocol is designed. Possession of one key must not silently prove control of the other.

## Prototype sequence

### P0 — First live message
- Run a minimal LXMF client on an available Android/desktop environment.
- Exchange messages with Sideband or another independent LXMF endpoint.
- Record the exact interface/network configuration and whether internet access is absent.
- Verify both directions, restart persistence, and observed delivery behavior.

### P1 — Offline and propagation behavior
- Test recipient offline, then reconnect.
- Use an available propagation node; record what is stored, when it is delivered, and what happens when propagation is unavailable.
- Do not claim offline delivery until observed.

### P2 — Independent infrastructure
- Test across two devices and a third-party transport/propagation node not operated by the UA project.
- Confirm the participant endpoint does not need to own or administer intermediate nodes.
- Test failure cases: relay unavailable, no route, incompatible configuration, and delayed delivery.

### P3 — UA integration
- Add a small UA messaging adapter around LXMF.
- Preserve transport-neutral service boundaries.
- Design persona-to-transport identity binding separately and explicitly.
- Add richer discovery only when a demonstrated user problem requires it.

## Prototype acceptance criteria

A test report should distinguish:
- **Implemented:** code exists.
- **CI-verified:** automated checks pass.
- **Device-tested:** software runs on the actual device.
- **Network-tested:** endpoints exchanged real messages over the stated interface.
- **Independently verified:** a second person/device confirmed the exchange.

A green CI run is not evidence of live network delivery.

## Working method

Build the smallest useful slice. Gather feedback from actual devices and networks. Record failures and constraints. Iterate. Expand only when the preceding slice works well enough to justify it.

**Non-goal for the first prototype:** solving global infrastructure reputation, universal discovery, federation governance, or a complete offline-first UA application before the first real message exchange.
