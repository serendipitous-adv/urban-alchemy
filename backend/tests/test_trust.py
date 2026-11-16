import unittest
from datetime import datetime, timedelta, timezone

from ua_alpha.database import Database
from ua_alpha.identity import Persona, PersonaKeypair
from ua_alpha.trust import InvitationError, PeerInvitation, TrustStore, UnknownInviter


class PeerTrustTests(unittest.TestCase):
    def setUp(self):
        self.db = Database()
        self.store = TrustStore(self.db)
        self.inviter_key = PersonaKeypair.generate()
        self.inviter = Persona.create(self.inviter_key)
        self.invitee = Persona.create(PersonaKeypair.generate())
        self.trusted = {self.inviter.persona_id: self.inviter.public_key}
        self.now = datetime(2026, 11, 15, 12, tzinfo=timezone.utc)

    def tearDown(self):
        self.db.close()

    def invite(self, **kwargs):
        return PeerInvitation.create(
            inviter_persona_id=self.inviter.persona_id, keypair=self.inviter_key,
            scopes=["events:receive", "directory:read"],
            recipient_persona_id=self.invitee.persona_id,
            now=self.now, **kwargs
        )

    def test_signed_invitation_requires_explicit_acceptance_and_grants_exact_scopes(self):
        invitation = self.invite()
        grant = self.store.accept(
            invitation, recipient_persona_id=self.invitee.persona_id,
            trusted_inviter_keys=self.trusted, now=self.now
        )
        self.assertTrue(self.store.has_scope(self.inviter.persona_id, "events:receive", self.now))
        self.assertTrue(self.store.has_scope(self.inviter.persona_id, "directory:read", self.now))
        self.assertFalse(self.store.has_scope(self.inviter.persona_id, "events:send", self.now))
        self.assertTrue(self.store.revoke(grant, self.now))
        self.assertFalse(self.store.has_scope(self.inviter.persona_id, "events:receive", self.now))

    def test_unknown_inviter_is_not_automatically_trusted(self):
        with self.assertRaises(UnknownInviter):
            self.store.accept(self.invite(), recipient_persona_id=self.invitee.persona_id,
                              trusted_inviter_keys={}, now=self.now)

    def test_wrong_recipient_rejected(self):
        other = Persona.create(PersonaKeypair.generate())
        with self.assertRaises(InvitationError):
            self.store.accept(self.invite(), recipient_persona_id=other.persona_id,
                              trusted_inviter_keys=self.trusted, now=self.now)

    def test_expired_invitation_rejected(self):
        invitation = self.invite(ttl=timedelta(hours=1))
        with self.assertRaises(InvitationError):
            self.store.accept(invitation, recipient_persona_id=self.invitee.persona_id,
                              trusted_inviter_keys=self.trusted, now=self.now + timedelta(hours=2))

    def test_replay_rejected(self):
        invitation = self.invite()
        args = dict(recipient_persona_id=self.invitee.persona_id, trusted_inviter_keys=self.trusted, now=self.now)
        self.store.accept(invitation, **args)
        with self.assertRaises(InvitationError):
            self.store.accept(invitation, **args)

    def test_signature_tampering_and_scope_escalation_rejected(self):
        invitation = self.invite()
        changed = PeerInvitation(**{**invitation.__dict__, "scopes": ("directory:read", "events:receive", "admin:*")})
        with self.assertRaises(InvitationError):
            changed.verify(self.trusted, self.now)

    def test_issued_invitation_is_persisted(self):
        invitation = self.invite()
        self.store.issue(invitation)
        row = self.db.connection.execute("SELECT invitation_id FROM peer_invitations").fetchone()
        self.assertEqual(row["invitation_id"], invitation.invitation_id)


if __name__ == "__main__":
    unittest.main()
