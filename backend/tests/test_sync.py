import unittest

from ua_alpha.database import Database
from ua_alpha.events import EventError, EventStore, SignedEvent
from ua_alpha.identity import Persona, PersonaKeypair
from ua_alpha.sync import SyncBatch, SyncEngine, SyncError


class SyncEngineTests(unittest.TestCase):
    def setUp(self):
        self.db_a = Database()
        self.db_b = Database()
        self.key = PersonaKeypair.generate()
        self.persona = Persona.create(self.key)
        self.trust = {self.persona.persona_id: self.persona.public_key}
        self.store_a = EventStore(self.db_a)
        self.store_b = EventStore(self.db_b)
        self.a = SyncEngine(self.db_a, self.store_a)
        self.b = SyncEngine(self.db_b, self.store_b)

    def tearDown(self):
        self.db_a.close()
        self.db_b.close()

    def event(self, minute, name):
        return SignedEvent.create(
            event_type="ua.quest.published.v1",
            persona_id=self.persona.persona_id,
            keypair=self.key,
            payload={"quest": name},
            created_at=f"2026-11-15T12:{minute:02d}:00+00:00",
        )

    def test_batch_transfer_ack_and_resume(self):
        first, second, third = self.event(0, "one"), self.event(1, "two"), self.event(2, "three")
        for event in (first, second, third):
            self.store_a.ingest(event, self.trust)
        batch = self.a.prepare_outbound("node-b", limit=2)
        self.assertEqual([e.event_id for e in batch.events], [first.event_id, second.event_id])
        self.assertEqual(self.b.receive_batch("node-a", SyncBatch("node-a", batch.events, batch.cursor), self.trust), 2)
        self.assertEqual(self.b.receive_batch("node-a", SyncBatch("node-a", batch.events, batch.cursor), self.trust), 0)
        self.a.acknowledge_outbound("node-b", batch.cursor)
        resumed = self.a.prepare_outbound("node-b", limit=2)
        self.assertEqual([e.event_id for e in resumed.events], [third.event_id])

    def test_receive_cursor_commits_with_events(self):
        event = self.event(0, "one")
        batch = SyncBatch("node-a", (event,), event.event_id)
        self.assertEqual(self.b.receive_batch("node-a", batch, self.trust), 1)
        self.assertEqual(self.b.cursor("node-a", "receive"), event.event_id)
        self.assertIsNotNone(self.store_b.get(event.event_id))

    def test_invalid_batch_does_not_advance_cursor(self):
        event = self.event(0, "one")
        with self.assertRaises(EventError):
            self.b.receive_batch("node-a", SyncBatch("node-a", (event,), event.event_id), {})
        self.assertIsNone(self.b.cursor("node-a", "receive"))
        self.assertIsNone(self.store_b.get(event.event_id))

    def test_batch_order_and_cursor_are_checked(self):
        first, second = self.event(0, "one"), self.event(1, "two")
        with self.assertRaises(SyncError):
            self.b.receive_batch("node-a", SyncBatch("node-a", (second, first), first.event_id), self.trust)
        with self.assertRaises(SyncError):
            self.b.receive_batch("node-a", SyncBatch("node-a", (first,), second.event_id), self.trust)

    def test_acknowledgement_must_reference_stored_event(self):
        with self.assertRaises(SyncError):
            self.a.acknowledge_outbound("node-b", "not-a-real-event")

    def test_sync_does_not_grant_trust_or_peer_authority(self):
        event = self.event(0, "one")
        with self.assertRaises(EventError):
            self.b.receive_batch("node-a", SyncBatch("node-a", (event,), event.event_id), {})
        self.assertEqual(self.store_b.export_after(), [])


if __name__ == "__main__":
    unittest.main()
