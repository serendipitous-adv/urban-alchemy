"""Embedded Reticulum/LXMF runtime helpers for the Android messenger.

Import checks are side-effect free. start_runtime creates or loads a dedicated,
persistent LXMF identity in app-private storage and starts the configured RNS
stack. It does not send messages. Announcing is a separate explicit action.
"""

import json
import threading
from pathlib import Path

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
            "display_name": display_name,
            "identity_persisted": identity_path.exists(),
            "reticulum_started": True,
            "announce_sent": False,
        }
        _runtime_state = {
            "reticulum": reticulum,
            "router": router,
            "identity": identity,
            "source": source,
            "public_info": public_info,
        }
        return json.dumps(public_info)


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
