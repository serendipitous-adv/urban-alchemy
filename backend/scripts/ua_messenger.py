"""Minimal Urban Alchemy LXMF messenger for live interoperability testing.

This is a transport prototype, not the production UA account/authentication layer.
It uses a dedicated persistent Reticulum identity and the standard LXMF delivery
protocol so it can exchange messages with Sideband and other LXMF clients.
"""
from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Urban Alchemy LXMF live messenger")
    parser.add_argument("--name", default="Urban Alchemy", help="Public LXMF display name")
    parser.add_argument("--to", help="Recipient LXMF destination hash (32 hex characters)")
    parser.add_argument("--storage", type=Path, default=Path.home() / ".urban-alchemy" / "messenger")
    parser.add_argument("--rns-config", type=Path, help="Optional Reticulum config directory")
    parser.add_argument("--path-timeout", type=int, default=90)
    parser.add_argument("--opportunistic", action="store_true", help="Use single-packet delivery instead of a direct link")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        import LXMF
        import RNS
    except ImportError:
        print("Missing Reticulum dependencies. Install with: python -m pip install rns lxmf", file=sys.stderr)
        return 2

    if args.path_timeout < 1 or args.path_timeout > 600:
        print("--path-timeout must be between 1 and 600 seconds", file=sys.stderr)
        return 2

    args.storage.mkdir(parents=True, exist_ok=True)
    identity_path = args.storage / "delivery_identity"
    router_path = args.storage / "lxmf"
    router_path.mkdir(parents=True, exist_ok=True)

    # Keep this transport identity stable across restarts. It is deliberately
    # separate from the UA persona key until the identity-binding protocol lands.
    if identity_path.exists():
        identity = RNS.Identity.from_file(str(identity_path))
        if identity is None:
            print(f"Could not load Reticulum identity at {identity_path}", file=sys.stderr)
            return 2
    else:
        identity = RNS.Identity()
        identity.to_file(str(identity_path))

    reticulum = RNS.Reticulum(configdir=str(args.rns_config) if args.rns_config else None)
    router = LXMF.LXMRouter(storagepath=str(router_path))
    source = router.register_delivery_identity(identity, display_name=args.name, stamp_cost=8)
    if source is None:
        print("LXMF could not register a delivery identity.", file=sys.stderr)
        return 2

    def on_message(message):
        try:
            body = message.content_as_string()
        except Exception:
            body = str(message.content)
        sender = RNS.hexrep(message.source_hash, delimit=False)
        title = message.title_as_string() if hasattr(message, "title_as_string") else ""
        stamp = "verified" if getattr(message, "signature_validated", False) else "unverified"
        print(f"\n\n[INBOUND {stamp}] {sender} {('— ' + title) if title else ''}\n{body}\n> ", end="", flush=True)

    def status_time():
        return datetime.now().astimezone().isoformat(timespec="seconds")

    def on_outbound_delivery(message):
        state = message.state
        if state == LXMF.LXMessage.DELIVERED:
            label = "DELIVERED — recipient delivery proof received"
        elif state == LXMF.LXMessage.SENT:
            label = "SENT — transmitted/accepted by network; recipient receipt not confirmed"
        else:
            label = f"DELIVERY UPDATE — LXMF state {state}"
        message_id = getattr(message, "hash", None)
        message_id = message_id.hex() if message_id else "pending"
        print(f"\n\n[{status_time()}] [OUTBOUND {label}] {message_id}\n> ", end="", flush=True)

    def on_outbound_failed(message):
        message_id = getattr(message, "hash", None)
        message_id = message_id.hex() if message_id else "unknown"
        print(f"\n\n[{status_time()}] [OUTBOUND FAILED] {message_id}\nLXMF could not complete delivery.\n> ", end="", flush=True)

    router.register_delivery_callback(on_message)
    router.announce(source.hash)

    own_hash = source.hash.hex()
    print("\nUrban Alchemy Messenger — LXMF prototype")
    print(f"Your address: {own_hash}")
    print("Share this 32-character address with the other person.")
    print("Messages are end-to-end encrypted by LXMF. Keep this identity file private.")
    print("Commands: /to ADDRESS, /announce, /quit")
    print("")

    recipient_hash = None
    if args.to:
        try:
            recipient_hash = bytes.fromhex(args.to.strip())
        except ValueError:
            print("--to must be a hexadecimal LXMF address", file=sys.stderr)
            return 2
        if len(recipient_hash) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 8:
            print("--to must be exactly 32 hexadecimal characters", file=sys.stderr)
            return 2

    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nClosing messenger.")
            break

        if not line:
            continue
        if line == "/quit":
            break
        if line == "/announce":
            router.announce(source.hash)
            print("Announce sent.")
            continue
        if line.startswith("/to "):
            raw = line[4:].strip()
            try:
                candidate = bytes.fromhex(raw)
            except ValueError:
                print("Address must be hexadecimal.")
                continue
            if len(candidate) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 8:
                print("Address must be exactly 32 hexadecimal characters.")
                continue
            recipient_hash = candidate
            print(f"Recipient set to {raw.lower()}.")
            continue
        if recipient_hash is None:
            print("Set a recipient first: /to 32-character-LXMF-address")
            continue

        if not RNS.Transport.has_path(recipient_hash):
            print("Looking up recipient path...")
            RNS.Transport.request_path(recipient_hash)
            deadline = time.monotonic() + args.path_timeout
            while not RNS.Transport.has_path(recipient_hash) and time.monotonic() < deadline:
                time.sleep(0.2)
            if not RNS.Transport.has_path(recipient_hash):
                print("No route found yet. Check both devices are on the same Reticulum network, then try /announce.")
                continue

        recipient_identity = RNS.Identity.recall(recipient_hash)
        if recipient_identity is None:
            print("Recipient identity is not available yet. Ask them to announce and retry.")
            continue
        destination = RNS.Destination(
            recipient_identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery"
        )
        method = LXMF.LXMessage.OPPORTUNISTIC if args.opportunistic else LXMF.LXMessage.DIRECT
        message = LXMF.LXMessage(
            destination, source, line, desired_method=method, include_ticket=True
        )
        message.register_delivery_callback(on_outbound_delivery)
        message.register_failed_callback(on_outbound_failed)
        router.handle_outbound(message)
        print(f"[{status_time()}] Queued with LXMF. Waiting for a delivery status update...")

    # Reticulum/LXMF own background workers; allow pending writes to settle.
    time.sleep(0.5)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
