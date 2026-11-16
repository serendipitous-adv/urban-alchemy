import unittest

from ua_alpha.database import Database
from ua_alpha.events import EventStore, SignedEvent
from ua_alpha.identity import Persona, PersonaKeypair
from ua_alpha.sync import SyncBatch, SyncEngine
from ua_alpha.transport import (
    PeerAddress, PeerSession, SyncAcknowledgement, SyncCoordinator, TransportError,
)


class MemoryTransport:
    name = "internet"

    def __init__(self, receiver, receiver_session):
        self.receiver = receiver
        self.receiver_session = receiver_session

    def exchange(self, address, batch):
        self.receiver.receive_once(self.receiver_session, batch)
        return SyncAcknowledgement(peer_id=batch.peer_id, cursor=batch.cursor)


class TransportContractTests(unittest.TestCase):
    def setUp(self):
        self.a_db, self.b_db = Database(), Database()
        self.key = PersonaKeypair.generate()
        self.persona = Persona.create(self.key)
        self.trust = {self.persona.persona_id: self.persona.public_key}
        self.a_store, self.b_store = EventStore(self.a_db), EventStore(self.b_db)
        self.a_engine, self.b_engine = SyncEngine(self.a_db), SyncEngine(self.b_db)
        self.a_session = PeerSession("node-b", "internet", True, "session-a")
        self.b_session = PeerSession("node-a", "internet", True, "session-b")
        self.a = SyncCoordinator(self.a_engine, self.trust)
        self.b = SyncCoordinator(self.b_engine, self.trust)

    def tearDown(self):
        self.a_db.close()
        self.b_db.close()

    def test_same_sync_engine_runs_over_transport_contract(self):
        event = SignedEvent.create(
            event_type="ua.quest.published.v1",
            persona_id=self.persona.persona_id,
            keypair=self.key,
            payload={"title": "Meet at the lounge"},
            created_at="2026-11-15T12:00:00+00:00",
        )
        self.a_store.ingest(event, self.trust)
        transport = MemoryTransport(self.b, self.b_session)
        sent = self.a.send_once(
            self.a_session, transport, PeerAddress("internet", "https://peer.example")
        )
        self.assertEqual(sent, 1)
        self.assertIsNotNone(self.b_store.get(event.event_id))
        self.assertEqual(self.a_engine.cursor("node-b", "send"), event.event_id)

    def test_unauthenticated_session_is_rejected(self):
        bad = PeerSession("node-b", "internet", False, "session-a")
        with self.assertRaises(TransportError):
            self.a.send_once(
                bad, MemoryTransport(self.b, self.b_session),
                PeerAddress("internet", "https://peer.example")
            )

    def test_transport_mismatch_is_rejected(self):
        with self.assertRaises(TransportError):
            self.a.send_once(
                self.a_session, MemoryTransport(self.b, self.b_session),
                PeerAddress("reticulum", "destination-hash")
            )

    def test_acknowledgement_from_wrong_peer_is_rejected(self):
        class WrongPeer:
            name = "internet"
            def exchange(self, address, batch):
                return SyncAcknowledgement("impostor", batch.cursor)
        event = SignedEvent.create(
            event_type="ua.test", persona_id=self.persona.persona_id,
            keypair=self.key, payload={}, created_at="2026-11-15T12:00:00+00:00"
        )
        self.a_store.ingest(event, self.trust)
        with self.assertRaises(TransportError):
            self.a.send_once(
                self.a_session, WrongPeer(), PeerAddress("internet", "peer")
            )
        self.assertIsNone(self.a_engine.cursor("node-b", "send"))


if __name__ == "__main__":
    unittest.main()
