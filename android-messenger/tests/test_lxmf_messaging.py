import importlib
import json
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import Mock


PYTHON_SRC = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "python"
sys.path.insert(0, str(PYTHON_SRC))


class FakeIdentity:
    def __init__(self, create_keys=True):
        self.loaded_key = None

    def load_public_key(self, key):
        self.loaded_key = key
        return True

    @staticmethod
    def recall(destination_hash):
        return object()


class FakeDestination:
    OUT = 1
    SINGLE = 2

    def __init__(self, identity, direction, destination_type, app_name, aspect):
        self.identity = identity

    @staticmethod
    def hash(identity, app_name, aspect):
        return bytes.fromhex("a" * 32)


class FakeLXMessage:
    GENERATING = 0
    OUTBOUND = 1
    SENDING = 2
    SENT = 3
    DELIVERED = 4
    REJECTED = 5
    CANCELLED = 6
    FAILED = 7
    OPPORTUNISTIC = 8

    def __init__(self, destination, source, content, title, **kwargs):
        self.hash = bytes.fromhex("b" * 32)
        self.state = self.OUTBOUND
        self.progress = 0.0
        self.delivery_callback = None
        self.failed_callback = None

    def register_delivery_callback(self, callback):
        self.delivery_callback = callback

    def register_failed_callback(self, callback):
        self.failed_callback = callback


class LxmfMessagingTests(unittest.TestCase):
    def setUp(self):
        self.rns = types.ModuleType("RNS")
        self.rns.Identity = FakeIdentity
        self.rns.Destination = FakeDestination
        self.rns.Transport = types.SimpleNamespace(
            has_path=Mock(return_value=True),
            request_path=Mock(),
        )
        self.lxmf = types.ModuleType("LXMF")
        self.lxmf.LXMessage = FakeLXMessage
        sys.modules["RNS"] = self.rns
        sys.modules["LXMF"] = self.lxmf

        sys.modules.pop("lxmf_messaging", None)
        self.bridge = importlib.import_module("lxmf_messaging")
        self.bridge._events.clear()
        self.bridge._outbound.clear()
        self.router = Mock()
        self.source = types.SimpleNamespace(hash=bytes.fromhex("c" * 32))
        self.bridge.attach(self.router, self.source)

    def tearDown(self):
        sys.modules.pop("RNS", None)
        sys.modules.pop("LXMF", None)
        sys.modules.pop("lxmf_messaging", None)

    def test_no_route_requests_path_without_submitting(self):
        self.rns.Transport.has_path.return_value = False

        result = json.loads(
            self.bridge.send_message("d" * 32, "hello", "local-1")
        )

        self.assertFalse(result["accepted"])
        self.assertEqual(result["status"], "WAITING_FOR_ROUTE")
        self.rns.Transport.request_path.assert_called_once_with(bytes.fromhex("d" * 32))
        self.router.handle_outbound.assert_not_called()

    def test_unknown_recipient_identity_stays_waiting(self):
        self.rns.Identity.recall = staticmethod(lambda destination_hash: None)

        result = json.loads(
            self.bridge.send_message("d" * 32, "hello", "local-2")
        )

        self.assertFalse(result["accepted"])
        self.assertEqual(result["status"], "WAITING_FOR_IDENTITY")
        self.router.handle_outbound.assert_not_called()

    def test_successful_submission_emits_delivery_callback(self):
        result = json.loads(
            self.bridge.send_message("d" * 32, "hello", "local-3")
        )

        self.assertTrue(result["accepted"])
        self.assertEqual(result["status"], "SUBMITTED_TO_LXMF")
        self.router.handle_outbound.assert_called_once()
        message = self.router.handle_outbound.call_args.args[0]
        message.state = FakeLXMessage.DELIVERED
        message.delivery_callback(message)

        events = json.loads(self.bridge.peek_events())
        self.assertEqual(events[0]["type"], "outbound_status")
        self.assertEqual(events[0]["client_message_id"], "local-3")
        self.assertEqual(events[0]["state_name"], "DELIVERED")

    def test_inbound_event_is_retained_until_acknowledged(self):
        inbound = types.SimpleNamespace(
            hash=bytes.fromhex("e" * 32),
            source_hash=bytes.fromhex("d" * 32),
            destination_hash=self.source.hash,
            title=b"",
            timestamp=123,
            signature_validated=True,
            transport_encryption=True,
            title_as_string=lambda: "",
            content_as_string=lambda: "hello back",
        )
        self.bridge._on_inbound(inbound)

        first_read = json.loads(self.bridge.peek_events())
        second_read = json.loads(self.bridge.peek_events())
        self.assertEqual(first_read, second_read)
        self.assertEqual(first_read[0]["content"], "hello back")
        self.assertEqual(self.bridge.ack_events(1), 0)
        self.assertEqual(json.loads(self.bridge.peek_events()), [])


if __name__ == "__main__":
    unittest.main()
