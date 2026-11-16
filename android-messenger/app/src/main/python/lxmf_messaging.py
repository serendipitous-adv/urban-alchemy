"""Android-facing LXMF send/receive bridge.

This module deliberately delegates message transport, encryption, routing and
delivery proofs to LXMF/Reticulum. It does not implement a parallel socket
protocol. Kotlin should poll drain_events() on a worker dispatcher and persist
every inbound event before acknowledging it to the UI.
"""
import json
import threading
import time
import uuid

_lock = threading.RLock()
_router = None
_source = None
_events = []
_outbound = {}


def attach(router, source):
    """Attach the already-running LXMRouter and local delivery destination."""
    global _router, _source
    with _lock:
        _router = router
        _source = source
        router.register_delivery_callback(_on_inbound)
    return json.dumps({"ready": True, "destination": source.hash.hex()})


def _on_inbound(message):
    """LXMF callback; copy only serialisable fields while callback is active."""
    event = {
        "type": "inbound",
        "message_hash": getattr(message, "hash", None).hex()
            if getattr(message, "hash", None) else str(uuid.uuid4()),
        "source": message.source_hash.hex() if message.source_hash else None,
        "destination": message.destination_hash.hex() if message.destination_hash else None,
        "title": message.title_as_string() if message.title else "",
        "content": message.content_as_string() or "",
        "timestamp": int(message.timestamp * 1000) if message.timestamp else int(time.time() * 1000),
        "signature_validated": bool(message.signature_validated),
        "transport_encrypted": bool(message.transport_encryption),
    }
    with _lock:
        _events.append(event)


def _state_name(state):
    import LXMF
    names = {
        LXMF.LXMessage.GENERATING: "GENERATING",
        LXMF.LXMessage.OUTBOUND: "OUTBOUND",
        LXMF.LXMessage.SENDING: "SENDING",
        LXMF.LXMessage.SENT: "SENT",
        LXMF.LXMessage.DELIVERED: "DELIVERED",
        LXMF.LXMessage.REJECTED: "REJECTED",
        LXMF.LXMessage.CANCELLED: "CANCELLED",
        LXMF.LXMessage.FAILED: "FAILED",
    }
    return names.get(state, "UNKNOWN")


def send_message(recipient_hex, text, client_message_id):
    """Submit one opportunistic LXMF message; route discovery is non-blocking."""
    with _lock:
        if _router is None or _source is None:
            raise RuntimeError("Reticulum/LXMF transport is not started")
        router, source = _router, _source

    recipient_hex = recipient_hex.strip().lower()
    if len(recipient_hex) != 32:
        raise ValueError("Recipient must be a 32-character LXMF destination")
    if not text or not text.strip():
        raise ValueError("Message cannot be empty")
    if not client_message_id:
        raise ValueError("A stable client message ID is required")

    import LXMF
    import RNS

    destination_hash = bytes.fromhex(recipient_hex)
    if not RNS.Transport.has_path(destination_hash):
        RNS.Transport.request_path(destination_hash)
        return json.dumps({
            "accepted": False,
            "status": "WAITING_FOR_ROUTE",
            "client_message_id": client_message_id,
        })

    recipient_identity = RNS.Identity.recall(destination_hash)
    if recipient_identity is None:
        return json.dumps({
            "accepted": False,
            "status": "WAITING_FOR_IDENTITY",
            "client_message_id": client_message_id,
        })

    destination = RNS.Destination(
        recipient_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "lxmf", "delivery"
    )
    message = LXMF.LXMessage(
        destination, source, text, "",
        desired_method=LXMF.LXMessage.OPPORTUNISTIC,
        include_ticket=True
    )

    def on_delivery(updated):
        with _lock:
            _events.append({
                "type": "outbound_status",
                "client_message_id": client_message_id,
                "lxmf_hash": updated.hash.hex() if updated.hash else None,
                "state": int(updated.state),
                "state_name": _state_name(updated.state),
                "progress": float(updated.progress),
            })

    def on_failure(updated):
        with _lock:
            _events.append({
                "type": "outbound_status",
                "client_message_id": client_message_id,
                "lxmf_hash": updated.hash.hex() if updated.hash else None,
                "state": int(updated.state),
                "state_name": _state_name(updated.state),
                "progress": float(updated.progress),
            })

    message.register_delivery_callback(on_delivery)
    message.register_failed_callback(on_failure)
    router.handle_outbound(message)

    with _lock:
        _outbound[client_message_id] = message
    return json.dumps({
        "accepted": True,
        "status": "SUBMITTED_TO_LXMF",
        "client_message_id": client_message_id,
        "lxmf_hash": message.hash.hex() if message.hash else None,
    })


def peek_events(limit=100):
    """Read pending events without deleting them; Android acknowledges after DB commit."""
    limit = max(1, min(int(limit), 500))
    with _lock:
        return json.dumps(_events[:limit])


def ack_events(count):
    """Remove only the prefix Android has successfully persisted."""
    count = max(0, min(int(count), 500))
    with _lock:
        del _events[:count]
        return len(_events)


def drain_events(limit=100):
    """Compatibility helper for diagnostics; production Android uses peek/ack."""
    batch = json.loads(peek_events(limit))
    ack_events(len(batch))
    return json.dumps(batch)
