import unittest

from ua_alpha.database import Database
from ua_alpha.events import EventError, EventStore, SignedEvent
from ua_alpha.identity import Persona, PersonaKeypair


class SignedEventTests(unittest.TestCase):
    def setUp(self):
        self.db = Database()
        self.store = EventStore(self.db)
        self.key = PersonaKeypair.generate()
        self.persona = Persona.create(self.key)
        self.trust = {self.persona.persona_id: self.persona.public_key}

    def tearDown(self):
        self.db.close()

    def make_event(self):
        return SignedEvent.create(
            event_type="ua.quest.published.v1",
            persona_id=self.persona.persona_id,
            keypair=self.key,
            payload={"quest_id": "q-1", "version": 1},
            created_at="2026-11-15T12:00:00+00:00",
        )

    def test_signed_event_verifies_and_round_trips(self):
        event = self.make_event()
        self.assertTrue(event.verify(self.trust))
        self.assertEqual(SignedEvent.from_dict(event.to_dict()), event)

    def test_ingest_is_idempotent(self):
        event = self.make_event()
        self.assertTrue(self.store.ingest(event, self.trust))
        self.assertFalse(self.store.ingest(event, self.trust))
        self.assertEqual(len(self.store.export_after()), 1)

    def test_unknown_or_wrong_persona_key_is_rejected(self):
        event = self.make_event()
        with self.assertRaises(EventError):
            self.store.ingest(event, {})
        with self.assertRaises(EventError):
            self.store.ingest(event, {self.persona.persona_id: PersonaKeypair.generate().public_key_bytes})

    def test_modified_payload_is_rejected(self):
        event = self.make_event()
        altered = SignedEvent(**{**event.__dict__, "payload": {"quest_id": "q-2"}})
        self.assertFalse(altered.verify(self.trust))

    def test_cursor_exports_following_events(self):
        first = self.make_event()
        self.store.ingest(first, self.trust)
        second = SignedEvent.create(
            event_type="ua.quest.completed.v1", persona_id=self.persona.persona_id,
            keypair=self.key, payload={"quest_id": "q-2"},
            created_at="2026-11-15T12:01:00+00:00",
        )
        self.store.ingest(second, self.trust)
        self.assertEqual([e.event_id for e in self.store.export_after(first.event_id)], [second.event_id])

    def test_invalid_timestamp_and_payload_size_rejected(self):
        with self.assertRaises(EventError):
            SignedEvent.create(event_type="x", persona_id=self.persona.persona_id, keypair=self.key,
                               payload={}, created_at="not-a-date")
        with self.assertRaises(EventError):
            SignedEvent.create(event_type="x", persona_id=self.persona.persona_id, keypair=self.key,
                               payload={"large": "x" * 50000})


if __name__ == "__main__":
    unittest.main()
