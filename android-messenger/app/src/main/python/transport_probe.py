"""Embedded Reticulum/LXMF runtime helpers for the Android messenger.

Import checks are side-effect free. start_runtime creates or loads a dedicated,
persistent LXMF identity in app-private storage and starts the configured RNS
stack. It does not send messages. Announcing is a separate explicit action.
"""

import json
import re
import signal
import threading
from pathlib import Path

# RNS registers process signal handlers during startup. Chaquopy starts this
# runtime from a Kotlin worker thread, where Python signal.signal is not allowed.
signal.signal = lambda *args, **kwargs: None

_runtime_lock = threading.RLock()
_runtime_state = None


def check_runtime():
    import RNS
    import LXMF
    import cryptography

    return {
        "rns_imported": True,
        "lxmf_imported": True,
        "cryptography_version": getattr(cryptography, "__version__", "unknown"),
        "rns_version": getattr(RNS, "__version__", "unknown"),
    }


def start_runtime(storage_path, display_name="Urban Alchemy"):
    """Start RNS/LXMF and load/create the app's persistent delivery identity.

    storage_path must be an app-private directory supplied by Android.
    The Reticulum config enables local AutoInterface discovery only; it does not
    configure an internet relay or a public TCP listener.
    """
    global _runtime_state

    with _runtime_lock:
        if _runtime_state is not None:
            return json.dumps(_runtime_state["public_info"])

        import LXMF
        import RNS

        root = Path(storage_path)
        config_dir = root / "reticulum"
        router_dir = root / "lxmf"
        config_dir.mkdir(parents=True, exist_ok=True)
        router_dir.mkdir(parents=True, exist_ok=True)

        config_file = config_dir / "config"
        if not config_file.exists():
            config_file.write_text(
                "[reticulum]\n"
                "  enable_transport = No\n"
                "  share_instance = No\n"
                "\n"
                "[logging]\n"
                "  loglevel = 4\n"
                "\n"
                "[interfaces]\n"
                "  [[Local AutoInterface]]\n"
                "    type = AutoInterface\n"
                "    enabled = yes\n",
                encoding="utf-8",
            )

        identity_path = root / "delivery_identity"
        if identity_path.exists():
            identity = RNS.Identity.from_file(str(identity_path))
            if identity is None:
                raise RuntimeError("The saved Reticulum identity could not be loaded.")
        else:
            identity = RNS.Identity()
            if not identity.to_file(str(identity_path)):
                raise RuntimeError(
                    "Could not persist the Reticulum identity; refusing to start "
                    "with an address that may change after restart."
                )

        # Reticulum initializes its configured interfaces here. Android's
        # multicast lock is acquired by the Kotlin host before this call.
        reticulum = RNS.Reticulum(configdir=str(config_dir))
        router = LXMF.LXMRouter(storagepath=str(router_dir))
        source = router.register_delivery_identity(
            identity, display_name=display_name, stamp_cost=8
        )
        if source is None:
            raise RuntimeError("LXMF could not register the delivery identity.")

        public_info = {
            "destination": source.hash.hex(),
            "reticulum_identity_hash": identity.hash.hex(),
            "reticulum_public_key": identity.get_public_key().hex(),
            "display_name": display_name,
            "identity_persisted": identity_path.exists(),
            "reticulum_started": True,
            "announce_sent": False,
        }
        # Register the native LXMF receive callback before exposing the runtime.
        # The bridge uses LXMF's own message format, routing and delivery proofs.
        from lxmf_messaging import attach
        attach(router, source)

        _runtime_state = {
            "reticulum": reticulum,
            "router": router,
            "identity": identity,
            "source": source,
            "public_info": public_info,
        }
        return json.dumps(public_info)



def validate_contact_invite(invite_json):
    """Validate a public UA invite against its Reticulum public key."""
    try:
        invite = json.loads(invite_json)
    except Exception as exc:
        raise ValueError("This is not a valid Urban Alchemy invite.") from exc

    if not isinstance(invite, dict):
        raise ValueError("Invite must be a JSON object.")
    if invite.get("type") != "urban-alchemy-contact" or invite.get("version") != 1:
        raise ValueError("Unsupported contact invite format.")

    display_name = invite.get("display_name")
    identity_hash = invite.get("reticulum_identity_hash")
    public_key_hex = invite.get("reticulum_public_key")
    destination_hex = invite.get("lxmf_destination")
    player_id = invite.get("player_id")

    if not isinstance(display_name, str) or not display_name.strip() or len(display_name.strip()) > 80:
        raise ValueError("Invite has an invalid display name.")
    if not isinstance(identity_hash, str) or not re.fullmatch(r"[0-9a-f]{32}", identity_hash):
        raise ValueError("Invite has an invalid Reticulum identity hash.")
    if not isinstance(public_key_hex, str) or not re.fullmatch(r"(?:[0-9a-f]{2}){64}", public_key_hex):
        raise ValueError("Invite has an invalid Reticulum public key.")
    if not isinstance(destination_hex, str) or not re.fullmatch(r"[0-9a-f]{32}", destination_hex):
        raise ValueError("Invite has an invalid LXMF destination.")
    if player_id is not None and (not isinstance(player_id, str) or len(player_id) > 80):
        raise ValueError("Invite has an invalid Player ID.")

    import RNS
    public_identity = RNS.Identity(create_keys=False)
    if not public_identity.load_public_key(bytes.fromhex(public_key_hex)):
        raise ValueError("The Reticulum public key could not be loaded.")
    if public_identity.hash.hex() != identity_hash:
        raise ValueError("The public key does not match the stated Reticulum identity.")
    expected_destination = RNS.Destination.hash(public_identity, "lxmf", "delivery").hex()
    if expected_destination != destination_hex:
        raise ValueError("The LXMF destination does not belong to this Reticulum identity.")

    return json.dumps({
        "type": "urban-alchemy-contact",
        "version": 1,
        "display_name": display_name.strip(),
        "player_id": player_id,
        "reticulum_identity_hash": identity_hash,
        "reticulum_public_key": public_key_hex,
        "lxmf_destination": destination_hex,
        "verified": True,
    }, separators=(",", ":"))


def announce():
    """Announce the local LXMF destination; this does not prove peer receipt."""
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before announcing.")
        source = _runtime_state["source"]
        _runtime_state["router"].announce(source.hash)
        _runtime_state["public_info"]["announce_sent"] = True
        return json.dumps({
            "announce_submitted": True,
            "destination": source.hash.hex(),
        })



def send_message(recipient_hex, text, client_message_id, recipient_public_key=None):
    """Submit a message through the active LXMF router."""
    from lxmf_messaging import send_message as lxmf_send
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before sending.")
    return lxmf_send(recipient_hex, text, client_message_id, recipient_public_key)


def drain_events(limit=100):
    """Drain inbound messages and outbound delivery-state changes."""
    from lxmf_messaging import drain_events as lxmf_drain
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before reading messages.")
    return lxmf_drain(limit)



def peek_events(limit=100):
    """Read pending bridge events without acknowledging them."""
    from lxmf_messaging import peek_events as lxmf_peek
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before reading messages.")
    return lxmf_peek(limit)


def ack_events(count):
    """Acknowledge bridge events only after Android has persisted the batch."""
    from lxmf_messaging import ack_events as lxmf_ack
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before acknowledging messages.")
    return lxmf_ack(count)


def create_contact_invite(display_name, player_id=None):
    """Export public contact details for sharing; never exports private keys."""
    with _runtime_lock:
        if _runtime_state is None:
            raise RuntimeError("Start Reticulum before creating a contact invite.")
        identity = _runtime_state["identity"]
        source = _runtime_state["source"]
        invite = {
            "type": "urban-alchemy-contact",
            "version": 1,
            "display_name": str(display_name or "Urban Alchemy").strip()[:80],
            "player_id": str(player_id) if player_id else None,
            "reticulum_identity_hash": identity.hash.hex(),
            "reticulum_public_key": identity.get_public_key().hex(),
            "lxmf_destination": source.hash.hex(),
        }
        return json.dumps(invite, separators=(",", ":"))
