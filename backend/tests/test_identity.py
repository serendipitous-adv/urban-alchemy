import unittest

from ua_alpha.identity import (
    Persona, PersonaKeypair, create_key_rotation, verify_key_rotation, persona_from_rotation,
)


class PersonaIdentityTests(unittest.TestCase):
    def test_persona_key_signs_and_verifies(self):
        key = PersonaKeypair.generate()
        persona = Persona.create(key)
        message = b"ua.quest.accepted.v1"
        self.assertTrue(persona.verify(message, key.sign(message)))
        self.assertFalse(persona.verify(b"different event", key.sign(message)))

    def test_personas_have_independent_keys_and_identifiers(self):
        first_key, second_key = PersonaKeypair.generate(), PersonaKeypair.generate()
        first, second = Persona.create(first_key), Persona.create(second_key)
        self.assertNotEqual(first.persona_id, second.persona_id)
        self.assertNotEqual(first.public_key, second.public_key)

    def test_key_rotation_preserves_persona_id_and_verifies(self):
        old_key, new_key = PersonaKeypair.generate(), PersonaKeypair.generate()
        persona = Persona.create(old_key)
        rotation = create_key_rotation(persona, old_key, new_key, sequence=1)
        self.assertTrue(verify_key_rotation(rotation, old_key.public_key_bytes))
        next_persona = persona_from_rotation(rotation)
        self.assertEqual(next_persona.persona_id, persona.persona_id)
        self.assertEqual(next_persona.public_key, new_key.public_key_bytes)

    def test_rotation_rejects_wrong_previous_key_or_sequence(self):
        old_key, new_key = PersonaKeypair.generate(), PersonaKeypair.generate()
        persona = Persona.create(old_key)
        rotation = create_key_rotation(persona, old_key, new_key, sequence=1)
        self.assertFalse(verify_key_rotation(rotation, PersonaKeypair.generate().public_key_bytes))
        with self.assertRaises(ValueError):
            create_key_rotation(persona, old_key, new_key, sequence=0)

    def test_rotation_cannot_be_created_by_unregistered_key(self):
        registered, impostor, successor = (
            PersonaKeypair.generate(), PersonaKeypair.generate(), PersonaKeypair.generate()
        )
        persona = Persona.create(registered)
        with self.assertRaises(ValueError):
            create_key_rotation(persona, impostor, successor, sequence=1)


if __name__ == "__main__":
    unittest.main()
