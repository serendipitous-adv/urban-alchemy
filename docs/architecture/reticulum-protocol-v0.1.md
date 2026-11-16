# Urban Alchemy Reticulum Protocol Specification

**Document:** UA-RNS-PROTOCOL  
**Version:** 0.1-draft  
**Status:** Phase 0 — architecture baseline; implementation details remain subject to prototype validation  
**Date:** 2026-11-15  
**Target repository:** `serendipitous-adv/urban-alchemy`  
**Target branch:** `phase-0/reticulum-protocol-v0.1`

---

## 1. Purpose and scope

Urban Alchemy (UA) is a decentralized, real-world community and quest ecosystem built as an application layer over the Reticulum Network Stack (RNS).

This specification defines the initial architectural contract for UA nodes, identities, service discovery, application messages, event records, local persistence, synchronization, and security boundaries. It is intended to guide a two-node technical prototype, not to claim that all distributed-systems problems are already solved.

The governing direction is **Reticulum-native**:

- UA application-to-application networking uses Reticulum.
- Internet connectivity may be one available Reticulum transport, but is not a prerequisite.
- Nodes can operate on isolated local networks and later interconnect.
- No central UA server, identity provider, database, or directory is required for ordinary operation.
- Conventional local databases, browsers, and operating systems remain permitted implementation tools.

### 1.1 Non-goals for Phase 0

This specification does not attempt to finalize:

- A global consensus protocol or blockchain.
- Universal identity or reputation.
- Guaranteed anonymity or traffic-analysis resistance.
- A complete geographic mapping protocol.
- AI model hosting or inference standards.
- Transferable SCAF tokens.
- A final mobile/desktop UI implementation.
- A production-ready multi-master database.

These topics may be addressed in later protocol revisions.

## 2. Normative language

The terms **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are used as requirements language.

Where this document labels a choice **provisional**, it is a design candidate that must be validated in Phase 1 before it becomes a stable interoperability requirement.

## 3. Governing principles

1. **No central dependency:** no central server is required for basic operation.
2. **Local sovereignty:** node operators and communities control their own services, records, policies, and publication.
3. **Participant-controlled identity:** participants control their persona keys and choose what identity information to disclose.
4. **Offline-first:** disconnection is a normal operating condition.
5. **Verifiable events:** meaningful state changes are represented as authenticated application events.
6. **Selective replication:** nodes exchange only data they are authorized and configured to receive.
7. **Protocol over platform:** independent clients can interoperate with compatible UA nodes.
8. **Progressive emergence:** one local node or community can be useful before it joins a larger network.
9. **No implicit authority:** operating a transport node, directory, or service does not confer governance authority over participants or communities.

## 4. Layered architecture

### Layer 1 — Physical and link infrastructure

Reticulum-supported interfaces and transports, which may include local IP, Wi-Fi, Ethernet, radio, LoRa-capable interfaces, and other supported media.

UA MUST NOT assume that all links have broadband capacity, stable latency, public IP addresses, or continuous connectivity.

### Layer 2 — Reticulum Network Stack

RNS provides cryptographic identities, destinations, announcements, routing, links, requests, and resource transfer.

UA MUST use supported RNS APIs and MUST NOT fork or redefine core RNS packet formats for ordinary application features.

The default RNS MTU is 500 bytes. UA MUST keep discovery and control messages compact and MUST NOT assume that a single packet can carry an arbitrary application object. The default MTU MUST NOT be changed for UA compatibility.

### Layer 3 — UA protocol

UA defines application-level identities/personas, service descriptors, authorization, event formats, object identifiers, synchronization, and domain rules.

### Layer 4 — UA applications

Clients and services for quests, Guilds, Patches, SCAF, directories, maps, governance, and optional AI stewards.

A client MAY communicate with a local UA node through a local IPC or loopback interface. Inter-node UA application traffic MUST use Reticulum.

## 5. Node model

A **UA node** is an independently operated runtime that participates in the UA ecosystem. A node may run on a laptop, phone, desktop, single-board computer, or server.

A node is not synonymous with a Reticulum transport node. A UA application node MAY be a non-transport RNS instance. Transport forwarding is an optional operator capability and MUST NOT confer application-level authority.

A node MAY host multiple services, including:

- Identity/persona service
- Quest service
- Guild service
- Patch service
- SCAF ledger service
- Directory/discovery service
- Local storage and synchronization service
- Optional steward/AI service

Services MUST be independently addressable and independently permissioned. A single physical machine MAY host several services.

### 5.1 Node roles

The initial implementation SHOULD distinguish capabilities, not impose a rigid hierarchy:

- **Participant node:** client-facing services and a participant's local data.
- **Community node:** one or more community services and community-controlled records.
- **Directory node:** optional index of published service descriptors.
- **Transport node:** forwards Reticulum traffic when configured to do so.
- **Steward node:** optional AI-assisted service with explicitly granted permissions.

A node MAY perform more than one role. No role is globally privileged.

## 6. Identity and persona architecture

### 6.1 Decision D-001 — Hybrid identity model

**Accepted.** UA uses an offline master recovery key with independently generated cryptographic personas.

The master recovery key is not the participant's everyday network identity. Each persona has its own independently generated RNS identity/key material and application profile.

Requirements:

1. Persona key material MUST be generated independently using secure cryptographic randomness.
2. Persona private keys MUST remain under the participant's control.
3. The master recovery key MUST be stored offline and MUST NOT be required for ordinary participation.
4. Persona keys MUST NOT be deterministically derived from a shared master seed in a way that makes compromise or correlation of one persona expose the others.
5. A public persona record MUST NOT include a master identity, master public key, recovery-manifest identifier, or other stable cross-persona correlation handle.
6. Recovery manifests and master-to-persona associations MUST remain private by default.
7. Recovery proofs MUST be selectively disclosed to the party that needs to evaluate a recovery.
8. A recovered key MUST NOT automatically restore Guild membership, SCAF balances, reputation, or other community-recognized rights. Relevant communities or record authorities apply their own recovery policies.
9. A recovery mechanism MUST NOT create a universal administrator capable of silently impersonating a persona.

### 6.2 Persona model

A persona is the network-facing identity used for a particular social or operational context. A participant MAY maintain multiple personas, for example a general persona, a Guild-specific persona, or a temporary event persona.

A persona record SHOULD contain only the fields necessary for the relevant service, such as:

- Persona protocol version
- Persona public-key / RNS identity reference
- Display name or pseudonym
- Optional profile metadata
- Optional service references
- Optional proof or credential references
- Record version and signature

The participant's legal identity is not required by the protocol.

### 6.3 Key lifecycle

Phase 1 MUST test persona creation, secure persistence, backup, rotation, loss, revocation, and recovery.

At minimum, the protocol MUST distinguish:

- Normal key rotation authorized by the existing persona key.
- Recovery after loss of the existing persona key.
- Revocation after suspected compromise.
- Community-specific recognition of a replacement persona.

A recovery proof may establish continuity to a chosen verifier; it MUST NOT be treated as proof of legal identity or automatically broadcast as a public link between personas.


### 6.3.1 Recovery-key hierarchy — accepted design direction

Each persona MUST have its own independently generated recovery key, separate from both its everyday persona key and the participant's offline master recovery key.

- The **persona key** signs ordinary persona activity and may authorize routine key rotation.
- The **persona recovery key** authorizes recovery of that persona if its everyday key is lost or compromised.
- The **offline master recovery key** certifies the persona-specific recovery key and may revoke or replace that recovery authorization under a separately specified recovery procedure. It is not used for ordinary persona recovery operations.

The master key MUST NOT be able to recreate or impersonate the persona's lost private key. Recovery creates a replacement persona/key relationship and a verifiable continuity claim; it does not resurrect the old secret key.

Persona recovery keys MUST be independently generated and MUST NOT be derived from a shared master seed. Compromise of one persona's recovery key MUST NOT, by itself, expose or authorize recovery of other personas.

A recovery statement SHOULD identify the affected persona, replacement persona, recovery-key authority, recovery reason, issuance time, and any superseded recovery authorization. It MUST be disclosed selectively rather than globally announced by default.

The exact certificate format, revocation distribution, and recovery delay remain open for Phase 1 research and threat modeling. Community recognition remains independent: recovery does not automatically transfer membership, reputation, SCAF, or governance rights.

### 6.3.2 Master recovery-key loss — accepted MVP policy

For the MVP, the offline master recovery key is protected by multiple independently stored offline backups under the participant's control. Threshold/social recovery is out of scope.

Loss of the master key MUST NOT invalidate existing persona-specific recovery keys or the recovery authorizations already certified by it. Existing persona recovery paths continue to function according to their own policies. Without the master key, the participant cannot use that master to certify new persona recovery keys or issue master-level revocations.

Replacing a lost master key and re-establishing its authority is an unresolved, explicit recovery ceremony; it MUST NOT silently inherit authority merely because a new key claims the same participant identity. The protocol will not assume that an unverified replacement master can retroactively revoke existing persona recovery keys.

Backup procedures, secure storage media, geographic separation, and compromise response SHOULD be documented in the implementation's operational security guidance.

### 6.3.3 Compromised persona recovery key — accepted policy

A valid signature from a persona recovery key MUST be treated as authorization to initiate a recovery claim, not as an automatic, irreversible replacement of the persona's identity or authority.

When a recovery claim is received:

1. The claim MUST identify the affected persona, the proposed replacement persona/key, the recovery authorization being exercised, and sufficient freshness/replay-protection data.
2. The claim MUST be cryptographically verified against the persona recovery authorization previously certified by the offline master key.
3. The affected persona and recovery authorization SHOULD be marked as **recovery pending** or **recovery disputed** in the relevant local/community records. Implementations MUST preserve the signed claim and its provenance.
4. A valid claim MUST NOT, by itself, erase the prior persona, retroactively invalidate its signed history, or force every Guild, Patch, directory, or ledger authority to recognize the replacement.
5. Each relevant community or record authority MUST apply its own published recovery-recognition and dispute policy before transferring membership, reputation, governance rights, SCAF records, or other authority.
6. Where the offline master recovery key is available, it MAY issue a signed revocation of the compromised persona recovery key and certify a replacement recovery key. This action MUST be recorded as a distinct, verifiable event.
7. If the master key is unavailable, the persona recovery-key claim can still be evaluated under the relevant community's recovery policy, but it MUST NOT be represented as a master-authorized revocation or as proof that the recovery key was not stolen.
8. Conflicting claims, including activity from the old persona key after a recovery claim, MUST be retained as evidence and handled under explicit dispute policy rather than resolved by silent last-write-wins behavior.

A recovery-key signature proves use of that key; it does not prove that the key remains in the legitimate participant's possession. Recovery therefore establishes a claim of continuity that verifiers and communities can evaluate, not an unquestionable global identity transfer.

Challenge windows and recognition timelines MUST be defined by the relevant Guild, Patch, or record authority; the protocol MUST NOT impose one universal network-wide waiting period. A community MAY apply temporary, proportionate restrictions to sensitive actions while a recovery claim is pending or disputed. The policy SHOULD distinguish low-risk continuity actions from high-impact authority changes, such as governance control or ledger access. Communities SHOULD publish their recovery policy so participants know the applicable process.

The exact notification mechanism, evidence requirements, challenge procedures, and dispute-resolution process remain open for Phase 1 threat modeling and community-policy design.

### 6.3.4 Persona continuity and compromised everyday key — accepted design direction

UA MUST preserve the application-level persona as the stable social identity when an everyday persona signing key is compromised and a valid recovery path is available. Recovery SHOULD rotate the persona's active signing authority rather than require the participant to abandon the persona and create a socially unrelated identity.

This requirement is defined at the UA persona layer; it MUST NOT be interpreted as requiring the underlying RNS cryptographic identity to retain the same key material. An RNS identity is cryptographically bound to its key material. Where the RNS identity key itself is compromised or cannot safely be retained, the participant MUST create fresh RNS key material and a new RNS identity/destination as required by RNS.

The UA persona continuity mechanism MUST therefore support:
1. A stable, opaque UA persona identifier that is distinct from any one RNS identity/key.
2. A signed key-transition or recovery statement, authorized by the persona recovery process, binding the stable persona identifier to the replacement active key and new RNS identity/destination.
3. Explicit identification of the superseded key/identity and the effective transition sequence or version.
4. Verification by peers that the replacement key is authorized for the same UA persona, without implying that the old private key has been recovered.
5. Selective disclosure of continuity proofs; the protocol MUST NOT create a public cross-persona or master-identity directory as a side effect.
6. Community-specific recognition of the rotated key. Membership, reputation, governance rights, and ledger authority remain subject to the relevant community or record authority's policy.

A normal rotation MAY be authorized by the current persona key. A compromise/loss recovery MUST use the persona recovery procedure in Sections 6.3.1–6.3.3. If both the everyday and recovery keys are suspected compromised, the claim MUST be treated as disputed and evaluated under the relevant community policy; possession of either compromised key alone MUST NOT be treated as conclusive proof of rightful control.

Implementations MUST retain historical signatures and provenance under the keys that originally produced them. Key rotation changes future signing authority; it MUST NOT rewrite or invalidate historical records merely because the active key changed.

### 6.3.5 Selective proof of common control across personas — accepted design direction

A participant MAY maintain multiple personas without publicly linking them. UA SHOULD support a privacy-preserving way for a participant to demonstrate a narrowly scoped fact about one or more personas to a chosen verifier, such as that the participant can exercise the relevant recovery authority for both personas.

Requirements and boundaries:

1. Cross-persona relationships MUST remain private by default. UA MUST NOT publish a stable master identifier, common-control marker, or globally reusable correlation handle in ordinary persona records, announcements, directories, or public event metadata.
2. A proof MUST be scoped to its intended verifier and purpose wherever practical. Proofs SHOULD be unlinkable across different verifiers or sessions unless the participant explicitly consents to linkability.
3. A verifier MUST learn no more than the claim it needs to evaluate. A proof of common control MUST NOT automatically disclose legal identity, the offline master public key, unrelated personas, recovery manifests, or the participant's full persona inventory.
4. The participant MUST explicitly authorize disclosure. A Guild's request for proof MUST NOT create a general right to discover or correlate all of the participant's personas.
5. Proofs MUST be bound to a fresh challenge or session context to prevent replay and unintended reuse.
6. Verifiers MUST record the scope and result of the proof, not retain unnecessary secrets or broader relationship data.
7. Selective proof of common control MUST NOT itself transfer membership, reputation, governance rights, SCAF, or other community-recognized authority. Those remain subject to the relevant community or record authority's rules.

Zero-knowledge proofs or other privacy-preserving cryptographic techniques are preferred research candidates where they materially reduce disclosure and correlation. This is not yet a commitment to a particular proof system, credential format, or cryptographic library. Phase 1 research MUST compare the complexity, bandwidth, implementation maturity, offline usability, and privacy properties of candidate approaches. A simpler challenge-response or selectively disclosed credential MAY be used only where its disclosure and linkability limitations are explicit and acceptable for the use case.

The protocol MUST NOT claim that a proof is zero-knowledge, unlinkable, or anonymous unless the implemented construction and its threat model substantiate that claim. Metadata, network observation, verifier collusion, and repeated proof requests remain potential correlation channels and MUST be considered in the privacy analysis.


### 6.3.6 Granular persona relationships and association disclosure — accepted design direction

UA MUST support explicit, granular relationships between personas and between personas and communities. A participant MAY disclose a relationship to one verifier or community without making that relationship discoverable to the wider network.

Requirements:

1. Persona relationships MUST be represented as scoped assertions or credentials, not inferred from a shared public identifier or a globally visible master key.
2. A participant MAY disclose that two personas share a recovery authority to a chosen verifier while keeping that relationship private from other verifiers and the wider network.
3. A participant MAY disclose an association between a persona and a specific Guild, Patch, or other community without disclosing unrelated persona relationships.
4. Each disclosure MUST specify its scope, intended verifier or audience, purpose, and any applicable validity period or context.
5. Relationship disclosures SHOULD be independently revocable or supersedable without requiring deletion or replacement of the associated personas.
6. Revocation MUST be communicated to relevant verifiers or authorities where feasible. The protocol MUST NOT imply that revocation can erase information already learned, copied, or independently retained by a recipient.
7. A verifier MUST NOT infer broader common control, community membership, or cross-persona association from a narrowly scoped proof unless that inference is explicitly supported by the disclosed claim.
8. Relationship proofs SHOULD be challenge-bound and minimized to avoid replay and unnecessary correlation. Their metadata and repeated use MUST be included in privacy threat modeling.
9. Community-specific recognition remains separate from proof of association. A proof that two personas share control does not automatically merge their memberships, reputations, governance rights, or SCAF records.

The protocol SHOULD model relationship disclosure as a participant-controlled authorization rather than a permanent property of either persona. The wire format, credential model, revocation distribution, and practical limits of verifier-specific unlinkability remain open for Phase 1 research and prototype validation.


### 6.3.7 Limits on mandatory cross-persona linkage — accepted design direction

A Guild or other community MUST NOT make proof that multiple personas share common control a blanket prerequisite for ordinary participation.

A community MAY request a narrowly scoped proof for a specific, published purpose where the proof is proportionate to the risk or benefit involved (for example, enforcing a clearly defined one-per-participant benefit). Such a request MUST:

1. State the purpose, the rule being enforced, and why a less revealing method is insufficient.
2. Request only the minimum claim and scope necessary; it MUST NOT demand disclosure of a participant's full persona inventory or master recovery identity.
3. Be limited to the relevant action or benefit rather than becoming a general condition of membership or participation.
4. Provide a reasonable alternative where practicable for participants who decline or cannot provide the requested proof, while clearly explaining any specific benefit or action that cannot then be granted.
5. Be governed by the community's published policy and remain subject to participant choice; the protocol MUST NOT silently convert a request into universal identity-linkage authority.

Communities MAY define eligibility rules for scarce or explicitly one-per-person benefits, but SHOULD prefer privacy-preserving eligibility credentials, rate limits, or other less-linking mechanisms where they adequately address the abuse case. A proof request MUST NOT itself establish legal identity or broader authority.


### 6.3.8 Persona portability and community-scoped authority — accepted design direction

A participant controls their UA persona and MAY use it across Guilds, Patches, nodes, and other compatible UA communities. Persona portability MUST NOT imply automatic portability of community-granted authority.

Requirements:

1. The stable UA persona identifier and participant-controlled key-transition history SHOULD remain usable across compatible communities, subject to the participant's chosen disclosures and the protocol's privacy requirements.
2. A participant MAY carry and selectively present portable records or credentials whose issuer, scope, validity, and provenance can be independently verified.
3. Guild membership, Patch recognition, private conversations, internal reputation, governance rights, SCAF balances, and other community- or ledger-specific privileges MUST NOT transfer automatically merely because a persona is presented elsewhere.
4. Each community or record authority decides which external credentials or records it recognizes, under its published policies.
5. Private community data MUST NOT become portable by default. Its disclosure or export remains subject to the originating community's policy, applicable permissions, and participant rights.
6. A receiving community MUST be able to distinguish participant-controlled persona records from issuer-controlled credentials and community-specific decisions.
7. Leaving a community MUST NOT require surrendering the participant's persona or unrelated portable records. Conversely, retaining a persona MUST NOT force a community to continue recognizing revoked membership or privileges.
8. Implementations SHOULD provide clear provenance and scope when displaying imported credentials or records, so that recognition by one community is not misrepresented as universal reputation or authority.

The governing principle is **portable identity without portable authority**. Credential formats, export/import mechanics, revocation checks, and privacy-preserving presentation remain subject to Phase 1 research and interoperability testing.


### 6.3.9 Portable reputation evidence and local reputation judgments — accepted design direction

UA MUST distinguish portable evidence about activity or contributions from a community's interpretation of that evidence.

1. A participant MAY selectively present verifiable evidence such as completed quests, contributions, endorsements, issued credentials, or demonstrated skills to another community.
2. Portable evidence MUST preserve issuer attribution, provenance, scope, relevant context, and any expiration or revocation information needed to evaluate it.
3. Evidence MUST NOT be presented as a universal reputation score or as an automatically transferable community judgment.
4. Each receiving Guild, Patch, or other community independently decides whether and how to interpret external evidence under its own published rules.
5. Community-specific reputation judgments, trust labels, membership standing, and governance assessments remain local unless their issuer explicitly publishes a portable credential with a clearly defined scope.
6. A participant MUST be able to withhold evidence and choose which records to disclose, subject to any separately stated eligibility rule for a particular benefit or role.
7. Implementations SHOULD avoid aggregating portable evidence into a single global score, ranking, or cross-community trust metric by default.
8. Negative judgments and allegations require particular care: systems MUST preserve issuer and context, distinguish verified events from opinions or claims, and MUST NOT silently convert one community's dispute or sanction into a universal status.

The governing distinction is **portable evidence, local interpretation**. Evidence schemas, selective presentation, issuer revocation, expiry, and privacy-preserving aggregation remain subject to Phase 1 research and testing.


### 6.3.10 Participant data control, withdrawal, and distributed retention — accepted design direction

UA MUST distinguish participant-controlled personal data and disclosures from signed records already distributed to other nodes or relied upon by communities.

1. Participants MUST be able to edit or delete personal profile information and locally stored personal data, subject to clearly disclosed technical and legal constraints.
2. Participants MAY withdraw optional disclosures and MUST be able to stop publishing future activity through a persona or service.
3. Withdrawal or deletion MUST NOT be represented as guaranteed erasure of information already delivered to, copied by, backed up by, or independently retained by other nodes or recipients.
4. Previously distributed signed events MAY remain in recipient records so that provenance, accounting, governance, and dispute histories remain verifiable. Retention MUST have a defined purpose and MUST NOT be treated as an unrestricted license for further publication or reuse.
5. Communities and record authorities MAY retain records necessary for accounting, governance, safety, or dispute resolution under published retention and access policies. They SHOULD define retention periods, access controls, minimization, and deletion or archival procedures.
6. Nodes SHOULD support data minimization, bounded retention where feasible, deletion of unnecessary local copies, and propagation of deletion or withdrawal requests where the receiving node's policy and obligations permit.
7. A node receiving a withdrawal or deletion request MUST distinguish removing its own copy, ceasing future replication, restricting access, and recording a tombstone or withdrawal notice. These actions have different effects and MUST NOT be conflated.
8. Protocols and interfaces MUST communicate the limits of distributed deletion clearly. They MUST NOT promise that a user can erase all copies of a signed event after dissemination.
9. Sensitive personal information SHOULD NOT be placed in immutable or widely replicated event payloads when a reference, minimal claim, or separately permissioned record would suffice.

The governing principle is **participant control over future disclosure and personal data, without pretending that distributed history can always be recalled**. Data classification, retention schedules, deletion-request propagation, tombstone semantics, and any applicable legal obligations remain subject to Phase 1 research and implementation policy.

### 6.4 Identity, persona, membership, and reputation

These are separate concepts:

- **RNS identity:** cryptographic identity used by Reticulum.
- **Persona:** the participant-facing identity/profile presented to UA services.
- **Membership:** a community's recognition of a persona under that community's rules.
- **Reputation:** claims or records about contributions, scoped to their issuer and context.

UA MUST NOT create a mandatory global identity or global reputation authority.

## 7. Service discovery and visibility

### 7.1 Decision D-002 — Selective discovery

**Accepted.** UA supports three service visibility classes:

1. **Public:** intentionally published for discovery by any network participant.
2. **Trusted-network:** discoverable only by peers possessing the relevant network/community authorization or decryption capability.
3. **Invitation-only:** not listed in generally discoverable directories; access is initiated through an explicit invitation or out-of-band exchange.

The service owner chooses visibility. The default is selective publication; public publication MUST be an explicit choice.

### 7.2 Discovery is not authorization

Discovery visibility and access authorization MUST be implemented as separate checks.

A public service listing does not grant access to private data or privileged operations. A trusted-network listing does not automatically grant membership. An invitation to discover or contact a service does not automatically grant permission to read or modify every object it hosts.

Every protected operation MUST be authorized independently.

### 7.3 RNS announcements and application discovery

RNS announcements are signed discovery mechanisms and may distribute destination public-key information and application-specific data. UA MAY use RNS announcements to make public service endpoints discoverable.

UA MUST NOT place private profile data, private membership lists, private quest data, or confidential service metadata in globally visible announcement payloads.

RNS encrypted interface discovery using a Reticulum Network Identity is a mechanism for discovering eligible network interfaces. It MUST NOT be confused with application-level authorization or assumed to provide private UA service discovery automatically.

For trusted-network and invitation-only service discovery, UA MUST define application-level mechanisms, such as encrypted service descriptors, capability-bearing invitations, and authorized directory responses. The precise wire format is provisional and must be tested.

### 7.4 Service descriptor

A service descriptor SHOULD contain:

- UA protocol identifier and version
- Service type
- RNS destination reference
- Visibility class
- Operator or issuer reference, where appropriate
- Supported capabilities
- Optional expiration or refresh metadata
- Optional policy/document reference
- Descriptor version and authentication material

Descriptors MUST be minimal. Sensitive metadata MUST be delivered only through an authorized encrypted channel.

### 7.5 Directory behavior

Directories are optional indexes, not authorities. A directory MAY cache and relay published descriptors but MUST preserve their issuer and authentication information. Clients MUST be able to verify a descriptor independently of the directory that delivered it.

No single directory is required for the network to function.

## 8. Communication mechanisms

UA SHOULD select the Reticulum mechanism appropriate to the interaction:

| Mechanism | Intended UA use |
|---|---|
| RNS Announce | Public endpoint/service discovery with minimal metadata |
| RNS Link | Interactive sessions and authenticated request/response exchanges |
| RNS Requests | Bounded queries and commands |
| RNS Resources | Larger object and synchronization transfers |
| LXMF | Asynchronous messages, invitations, alerts, and store-and-forward communication |

LXMF is a messaging layer, not the authoritative replication protocol for UA application state.

A successful LXMF delivery MUST NOT by itself be interpreted as a committed quest, membership, or SCAF state change. Such changes require domain-specific signed events and validation.

## 9. UA application protocol envelope

All inter-node UA messages MUST identify the protocol and message type. The following fields are the initial logical envelope:

- `protocol`: stable UA protocol identifier
- `version`: major/minor protocol version
- `message_type`: defined message or event type
- `message_id`: unique identifier
- `sender_persona`: authenticated persona reference
- `created_at`: sender-declared timestamp
- `object_id`: optional target object
- `payload`: typed application data
- `signature`: application-level authenticity proof

The sender-declared timestamp is informational and MUST NOT be the sole basis for ordering, authorization, or conflict resolution. Nodes MUST tolerate clock skew and disconnected operation.

### 9.1 Encoding

Deterministic CBOR is the preferred encoding candidate because compactness and deterministic representation are useful on constrained links. This is **provisional** pending a Phase 1 interoperability test and a written canonicalization profile.

JSON MAY be used for human-readable local debugging or administrative interfaces, but MUST NOT be assumed to have a canonical byte representation for signatures.

### 9.2 Message sizing

Control messages and discovery payloads SHOULD remain small enough for low-bandwidth links. Larger objects MUST be transferred using an appropriate link/resource transfer rather than fragmented manually into ad hoc application packets.

Implementations MUST test behavior under the default 500-byte RNS MTU and over at least one constrained-bandwidth transport.

## 10. Event and object model

UA application state changes SHOULD be represented as authenticated events. Events are durable records, not merely transient notifications.

Initial event families include:

- `ua.quest.created`
- `ua.quest.updated`
- `ua.quest.joined`
- `ua.quest.completion_attested`
- `ua.quest.completion_validated`
- `ua.guild.created`
- `ua.guild.member_proposed`
- `ua.guild.member_accepted`
- `ua.patch.record_published`
- `ua.scaf.credit_issued`
- `ua.scaf.debit_recorded`
- `ua.governance.proposal`
- `ua.governance.decision`

The names are provisional and may be revised before protocol freeze.

Each event SHOULD include:

- Globally collision-resistant event identifier
- Event type and schema version
- Issuer persona/service
- Subject/object identifier
- Relevant prior version or causal reference
- Payload
- Sender-declared creation time
- Signature and verification context

An event MUST be authenticated before it is accepted into validated application state. Nodes SHOULD retain the original event bytes or a canonical representation sufficient for later verification.

### 10.1 Idempotency

Repeated delivery of the same event MUST NOT apply the same state transition twice. Implementations MUST deduplicate by stable event identifier and verify that a repeated identifier does not carry different content.

### 10.2 Event authenticity is not truth

A valid signature proves that a key signed a record. It does not prove that the record's claims are factually true, that a quest was honestly completed, or that an issuer had authority to make the claim.

Domain-specific validation and trust policies remain necessary.

## 11. Local persistence and replication

### 11.1 Local storage

SQLite is the initial local persistence candidate. Each node maintains its own data store. Local persistence is not a central database and does not imply that every node stores every object.

The implementation SHOULD separate:

- Received event log
- Validated application state/projections
- Service descriptors and discovery cache
- Authorization and trust records
- Private persona/key references
- Synchronization cursors and transfer state

Private keys MUST NOT be stored in plaintext in ordinary application tables or logs. Secure storage practices are platform-dependent and must be specified for each supported client.

### 11.2 Selective replication

Replication MUST be policy-driven and selective. A node may subscribe to public objects, specific Guilds, specific Patches, or explicit peer feeds.

Replication MUST preserve issuer signatures and provenance. A relay or directory MUST NOT become the apparent author of records it forwards.

Nodes MUST NOT assume that receipt of an event authorizes redistribution. Each data class requires a publication and replication policy.

### 11.3 Synchronization protocol

The Phase 1 prototype SHOULD implement a simple authenticated synchronization exchange:

1. Peers establish an RNS Link.
2. Each peer authenticates the relevant service/persona and checks authorization.
3. Peers exchange compact summaries of the object/event ranges they are permitted to share.
4. Peers request missing records.
5. Records are transferred in bounded batches or through RNS Resources where appropriate.
6. Receivers verify signatures, schemas, authorization, and duplicate status.
7. Valid records are persisted; projections are updated according to domain rules.
8. The exchange can resume after interruption.

A Merkle-tree or other efficient anti-entropy mechanism MAY be evaluated later. It is not required for the first two-node prototype.

### 11.4 Conflict resolution

There MUST NOT be a universal last-write-wins rule.

Conflict resolution is domain-specific:

- Immutable signed events are retained as competing claims where appropriate.
- Quest versions require explicit version/parent relationships and creator or governance policy.
- Membership changes are governed by the relevant Guild's rules.
- SCAF accounting uses issuer/ledger authority and explicit reconciliation rules.
- Governance outcomes are interpreted according to the applicable community constitution or policy.

If a node cannot resolve a conflict safely, it MUST preserve the conflicting evidence and mark the projection unresolved rather than silently discard a valid record.

## 12. SCAF accounting boundary

SCAF is an internal application ledger in the initial MVP, not a transferable cryptocurrency or global blockchain.

Existing product rules to preserve:

- A valid participant completion earns 10 SCAF.
- The quest creator earns 5 SCAF for each distinct player who validly completes the quest.
- Quest creation is open to any account.
- Completion is self-attested, with optional evidence.
- The initial ledger is internal and non-transferable.

Reticulum transports ledger events; it does not provide distributed consensus or prevent double spending.

The MVP SHOULD use a designated ledger issuer/authority for each account or accounting domain, with signed, append-only records and deterministic idempotency. Disconnected spending, delegated spending authority, escrow, and cross-ledger settlement are deferred until a separate accounting specification exists.

The protocol MUST NOT imply that a disconnected node can independently authorize conflicting debits against the same spendable balance.

## 13. Guilds and Patches

A Guild is an independently governed social structure. A Guild MAY operate its own services, membership policy, quest catalog, communication endpoints, and records.

A Patch is a geographic or social area of participation and does not imply ownership of land or centralized control.

A Guild may participate in multiple Patches; a Patch may contain or connect multiple Guilds.

Membership, governance, and access policies are community-defined. Guild and Patch creation is permissionless: anyone MAY create a Guild or Patch without approval from an existing Guild, Patch, or UA authority. Creation does not require registration with a central organization or inclusion in a directory.

A community's legitimacy is established through its participants, relationships, and recognition by others—not through central registration. Optional directories MAY index published communities to aid discovery, but directory listing or omission MUST NOT determine whether a Guild or Patch exists or is legitimate. A community SHOULD be able to publish its signing-key identity and governance policies so that other participants can verify its claims and make informed decisions about engagement.


### 13.1 Community autonomy and protocol-level governance boundaries — accepted design direction

UA MUST define shared interoperability, security, and minimum privacy requirements without imposing a universal constitution or a single political structure on every Guild or Patch.

1. Guilds define their own membership, internal governance, roles, decision procedures, and community policies.
2. Patches define their own local participation, coordination, and place-based policies, subject to the autonomy of participants and other communities.
3. Communities MAY adopt shared governance templates, protocols, or mutual agreements. Adoption MUST be voluntary unless a community has explicitly entered a binding agreement with another party.
4. UA protocol requirements govern technical interoperability, message validity, security boundaries, and the minimum protections necessary for compatible operation; they MUST NOT silently prescribe a universal internal governance model.
5. No central UA operator, directory, steward, or protocol maintainer has unilateral authority to override a Guild's or Patch's internal decisions merely by virtue of operating infrastructure or publishing software.
6. A community's autonomy does not authorize it to forge another community's records, impersonate participants, bypass cryptographic authorization, or claim control over nodes and data it does not govern.
7. Cross-community disputes and shared-resource arrangements SHOULD be handled through explicit agreements and mutually recognized procedures, not presumed central jurisdiction.
8. Implementations MUST distinguish protocol conformance from community legitimacy: a technically valid message does not make a community decision legitimate under that community's own rules.

The governing principle is **common protocol, plural governance**: shared technical rules enable cooperation while communities retain authority over their own internal arrangements.

## 14. Security and privacy requirements

The protocol MUST account for:

- Private-key theft and device compromise
- Malicious or malformed messages
- Replay and duplicate delivery
- Unauthorized service access
- Forged or invalid signatures
- Malicious directory entries
- Spam and resource exhaustion
- Traffic analysis and metadata leakage
- Compromised or dishonest node operators
- Clock skew and inconsistent local state
- Recovery-key compromise

Requirements:

1. Authenticate protected operations.
2. Authorize every protected operation separately from discovery.
3. Validate message schema, size, and resource limits before expensive processing.
4. Use stable event IDs and replay/deduplication checks.
5. Keep private data out of public announcements and directories.
6. Minimize persistent identifiers shared across personas.
7. Provide key rotation and revocation paths.
8. Preserve provenance when relaying records.
9. Treat node operators and directories as potentially curious or compromised.
10. Document that Reticulum encryption does not guarantee anonymity or prevent all traffic analysis.

## 15. Client-to-node interface

A local UI MAY communicate with its local UA runtime through a local API, IPC, or loopback interface. The local interface MUST enforce local access controls and MUST NOT expose private-key operations to untrusted origins.

Inter-node application communication MUST use Reticulum. A conventional web server or browser may be used as a local presentation layer, but a cloud-hosted web backend MUST NOT be a required application dependency.

The initial client may remain a web-style Guild House UI served locally by a UA node.

## 16. Dependency and compatibility baseline

The initial research baseline is the official Reticulum 1.6.0 documentation and release notes, dated November 2026.

- Use the official Python RNS implementation for the first prototype.
- Record exact RNS and LXMF versions in a dependency lockfile when implementation begins.
- Pin versions for reproducibility; do not float dependencies in the prototype.
- Verify API behavior against the installed package and official documentation.
- Test the exact LXMF release selected for the prototype rather than assuming LXMF delivery semantics are equivalent to application-state replication.
- Do not change RNS's default MTU for UA compatibility.

Dependency upgrades require regression tests for identity persistence, destination addressing, announcements, Links, Requests, Resources, LXMF delivery, and constrained-link behavior.

## 17. Phase 1 two-node prototype acceptance tests

The prototype is accepted only when the following tests pass:

1. Two independently operated UA nodes start without a central service.
2. Each node has its own persistent RNS identity and local store.
3. Public service discovery works using signed RNS announcements or a documented application discovery flow.
4. Trusted-network discovery exposes a service descriptor only to authorized peers.
5. Invitation-only discovery works through an explicit invitation without publishing private service metadata.
6. A public service listing does not grant access to private service operations.
7. Node A publishes a versioned quest record.
8. Node B discovers and retrieves the quest.
9. Node B creates a signed completion-attestation event.
10. Node A verifies the event's signature and applicable quest/issuer rules.
11. Duplicate delivery does not duplicate a state transition or reward.
12. Both nodes disconnect, continue local operation, and later synchronize missing authorized records.
13. Interrupted synchronization resumes without corrupting or duplicating records.
14. A third node can join through the supported discovery/invitation process without central registration.
15. The workflow succeeds on an isolated local network with no internet connection.
16. Invalid signatures, malformed payloads, unauthorized requests, replayed events, and oversized messages are rejected safely.
17. The prototype documents which operations are unavailable while disconnected and how unresolved conflicts are represented.
18. A valid persona recovery-key signature initiates a recorded pending/disputed recovery claim; it does not automatically erase the prior persona or force community recognition. A compromised recovery key can be revoked and replaced by a separately signed master-authorized event when the master key is available.
19. Recovery challenge windows and temporary restrictions are applied according to the relevant community/record authority's published policy, without a universal network-wide delay.
20. Compromise recovery preserves the stable UA persona identifier while binding it to fresh RNS key material/identity where necessary; historical signatures remain verifiable under their original keys.
21. A participant can selectively prove a narrowly scoped cross-persona common-control claim to an authorized verifier without publishing a global persona-linkage handle; proof-system claims are validated against an explicit threat model.
22. A participant can disclose a scoped association between personas or between a persona and a specific community, while withholding that relationship from other audiences; revocation affects future reliance but does not erase information already received.
23. Ordinary Guild participation does not require blanket proof of common control across personas; a narrowly scoped request for a published, proportionate purpose is handled under an explicit community policy and offers a reasonable alternative where practicable.
24. A persona can be used across communities and carry selectively disclosed, verifiable portable records, while Guild/Patch membership, private data, reputation, SCAF, and other local authority remain subject to the originating and receiving communities' policies.
25. Portable activity/contribution evidence retains issuer and context, while receiving communities independently interpret it; the system does not create a universal reputation score or automatically transfer local reputation judgments.
26. A participant can delete locally controlled personal data and withdraw optional/future disclosures; nodes distinguish local deletion, cessation of replication, access restriction, and retained signed history, without promising global erasure.
27. Interoperability and security rules are enforced at the protocol boundary while Guilds and Patches define their own governance; shared templates are voluntary and no central UA operator can unilaterally override community decisions.

## 18. Phase 0 open questions

These remain deliberately unresolved until research or prototype evidence is available:

- Final canonical encoding and signature-byte profile.
- Exact UA destination naming/aspect conventions.
- Exact service descriptor schema.
- Exact trusted-network discovery and invitation wire formats.
- Recovery manifest format and community recovery policies.
- Stable UA persona identifier and key-transition statement schema, including RNS identity replacement semantics.
- Privacy-preserving selective proof format for narrowly scoped cross-persona common-control claims.
- Scoped persona-relationship assertion/credential format, verifier and audience binding, and revocation/expiry distribution.
- Privacy-preserving eligibility mechanisms and alternatives to common-control proofs for one-per-participant benefits.
- Portable credential and record formats, provenance, revocation checking, and participant-controlled export/import semantics across communities.
- Portable reputation-evidence schemas, issuer attribution, revocation/expiry, selective presentation, and protections against global scoring or context collapse.
- Data classification, retention schedules, deletion/withdrawal propagation, tombstone semantics, and policy/legal constraints for distributed records.
- Governance interoperability: representation of community constitutions/policies, explicit cross-community agreements, and dispute procedures without introducing a central override authority.
- Optional community publication/verification profile: signing-key identity, governance-policy descriptor, and directory indexing semantics that preserve permissionless creation.
- Event identifier construction and canonicalization.
- Synchronization summary/anti-entropy algorithm.
- Storage encryption and secure key storage per platform.
- Service admission, rate limiting, and anti-spam policy.
- Exact RNS/LXMF dependency versions and compatibility matrix.
- Geographic object and privacy model.

## 19. Phase 0 exit criteria

Phase 0 is complete when:

- Architecture and terminology are agreed.
- Identity and selective-discovery decisions are recorded.
- The protocol envelope and event model are sufficiently specified to prototype.
- Trust, privacy, and replication boundaries are documented.
- Open questions are explicitly assigned to research or prototype validation.
- Phase 1 acceptance tests are implementable and measurable.

No production application implementation should begin until the Phase 0 exit criteria are reviewed.

---

## 20. Decision register

| ID | Decision | Status |
|---|---|---|
| D-001 | Hybrid identity: offline master recovery key; independently generated personas | Accepted |
| D-002 | Selective service discovery: public, trusted-network, invitation-only | Accepted |
| D-003 | Anyone may create quests with an account | Existing product decision |
| D-004 | Pseudonymous usernames/personas | Existing product decision |
| D-005 | Self-attested completion; optional evidence | Existing product decision |
| D-006 | Internal, non-transferable SCAF ledger for MVP | Existing product decision |
| D-007 | 10 SCAF participant reward; 5 SCAF creator royalty per distinct valid completer | Existing product decision |
| D-008 | Reticulum-native application networking; internet is optional transport | Accepted |
| D-009 | Each persona has an independently generated recovery key certified by the offline master recovery key | Accepted design direction |
| D-010 | MVP master-key protection uses independent offline backups; threshold/social recovery is deferred | Accepted |
| D-011 | A persona recovery-key signature initiates a recovery claim; replacement and community recognition are not automatic | Accepted |
| D-012 | Recovery challenge windows and recognition timelines are community-defined; no universal protocol-wide delay | Accepted |
| D-013 | Preserve the stable UA persona through key rotation/recovery; replace compromised RNS key material and bind fresh RNS identity to the same persona where required | Accepted design direction |
| D-014 | Support participant-controlled, privacy-preserving selective proofs of narrowly scoped common control across personas; investigate zero-knowledge techniques without prematurely selecting a proof system | Accepted design direction |
| D-015 | Persona relationships and association disclosures are granular, explicitly scoped, selectively disclosed, and independently revocable; revocation cannot erase information already received | Accepted design direction |
| D-016 | No blanket common-control proof requirement for ordinary Guild participation; narrowly scoped, proportionate requests require a published purpose and reasonable alternative where practicable | Accepted design direction |
| D-017 | Persona identity and selectively disclosed verifiable records are portable; community membership, private data, reputation, SCAF, and other authority do not transfer automatically | Accepted design direction |
| D-018 | Reputation evidence may be selectively portable with provenance; reputation judgments remain local to each community, with no universal score or automatic transfer | Accepted design direction |
| D-019 | Participants control personal data and future disclosure; distributed signed records may persist under purpose-limited published retention policies, and global erasure is not promised | Accepted design direction |
| D-020 | Common interoperability/security protocol with plural, autonomous Guild and Patch governance; shared templates are voluntary and no central UA authority has unilateral override power | Accepted design direction |
| D-021 | Permissionless Guild and Patch creation; directories aid discovery but do not confer legitimacy or determine existence | Accepted |

## 21. Research references

Primary references to verify during implementation:

- Reticulum manual: https://reticulum.network/manual/
- Reticulum understanding: https://reticulum.network/manual/understanding
- Reticulum API reference: https://reticulum.network/manual/reference
- Reticulum interface discovery: https://reticulum.network/manual/using.html
- Reticulum interface configuration: https://reticulum.network/manual/interfaces
- Reticulum upstream changelog: https://github.com/markqvist/Reticulum/blob/master/Changelog.md
- LXMF upstream project: https://github.com/markqvist/LXMF

This document is a UA application protocol proposal. It does not modify or supersede the Reticulum or LXMF specifications.
