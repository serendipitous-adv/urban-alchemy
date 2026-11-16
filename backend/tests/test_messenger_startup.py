import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "ua_messenger.py"


class MessengerStartupTests(unittest.TestCase):
    def test_refuses_to_start_when_identity_cannot_be_saved(self):
        class UnsavableIdentity:
            def to_file(self, path):
                return False

        fake_rns = types.ModuleType("RNS")
        fake_rns.Identity = UnsavableIdentity
        fake_lxmf = types.ModuleType("LXMF")

        spec = importlib.util.spec_from_file_location("ua_messenger_under_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        with tempfile.TemporaryDirectory() as storage:
            argv = ["ua_messenger.py", "--storage", storage]
            with patch.object(sys, "argv", argv), patch.dict(
                sys.modules, {"RNS": fake_rns, "LXMF": fake_lxmf}
            ):
                self.assertEqual(module.main(), 2)

            self.assertFalse((Path(storage) / "delivery_identity").exists())


if __name__ == "__main__":
    unittest.main()
