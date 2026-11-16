import unittest

from ua_alpha.database import Database
from ua_alpha.domain import DomainError, UrbanAlchemy

class QuestLedgerTests(unittest.TestCase):
    def setUp(self):
        self.db = Database(":memory:")
        self.ua = UrbanAlchemy(self.db)
        self.creator = self.ua.create_player("creator")
        self.player = self.ua.create_player("wanderer")
        self.quest = self.ua.create_quest(self.creator)
        self.v1 = self.ua.publish_version(
            self.quest, self.creator, title="Share a potluck", description="Gather together",
            instructions="Bring something to share", category="community",
        )

    def tearDown(self):
        self.db.close()

    def test_completion_posts_both_rewards_once(self):
        attempt = self.ua.accept_quest(self.quest, self.player)
        result = self.ua.complete_quest(attempt, self.player, "We shared dinner.")
        self.assertEqual((result.participant_reward, result.creator_royalty), (10, 5))
        retry = self.ua.complete_quest(attempt, self.player)
        self.assertTrue(retry.already_completed)
        self.assertEqual(self.ua.balance(self.player), 10)
        self.assertEqual(self.ua.balance(self.creator), 5)
        self.assertEqual(len(self.ua.ledger(self.player)), 1)

    def test_attempt_keeps_accepted_version_after_new_publication(self):
        attempt = self.ua.accept_quest(self.quest, self.player, self.v1)
        v2 = self.ua.publish_version(
            self.quest, self.creator, title="Share a bigger potluck", description="Gather together",
            instructions="Invite more neighbors", category="community",
        )
        self.assertNotEqual(self.v1, v2)
        stored = self.db.connection.execute("SELECT version_id FROM attempts WHERE id = ?", (attempt,)).fetchone()
        self.assertEqual(stored["version_id"], self.v1)

    def test_creator_cannot_accept_own_quest(self):
        with self.assertRaises(DomainError):
            self.ua.accept_quest(self.quest, self.creator)

    def test_one_attempt_per_player_per_quest(self):
        self.ua.accept_quest(self.quest, self.player)
        with self.assertRaises(DomainError):
            self.ua.accept_quest(self.quest, self.player)

    def test_published_versions_are_immutable(self):
        with self.assertRaises(Exception):
            self.db.connection.execute("UPDATE quest_versions SET title = 'tampered' WHERE id = ?", (self.v1,))
        with self.assertRaises(Exception):
            self.db.connection.execute("DELETE FROM quest_versions WHERE id = ?", (self.v1,))

    def test_ledger_is_append_only(self):
        attempt = self.ua.accept_quest(self.quest, self.player)
        self.ua.complete_quest(attempt, self.player)
        entry_id = self.ua.ledger(self.player)[0]["id"]
        with self.assertRaises(Exception):
            self.db.connection.execute("UPDATE ledger_entries SET amount = 999 WHERE id = ?", (entry_id,))
        with self.assertRaises(Exception):
            self.db.connection.execute("DELETE FROM ledger_entries WHERE id = ?", (entry_id,))

    def test_rewards_are_fixed_at_ten_and_five(self):
        row = self.db.connection.execute(
            "SELECT participant_reward, creator_reward FROM quest_versions WHERE id = ?", (self.v1,)
        ).fetchone()
        self.assertEqual((row["participant_reward"], row["creator_reward"]), (10, 5))

if __name__ == "__main__":
    unittest.main()
