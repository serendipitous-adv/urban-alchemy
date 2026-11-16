# Urban Alchemy Messenger — first live test

This is a deliberately small interoperability prototype. It uses the official
Reticulum Network Stack (RNS) and LXMF messaging protocol rather than inventing
a new message transport. It can exchange messages with Sideband and other
LXMF-compatible clients.

## What this prototype proves

- A persistent Reticulum delivery identity can announce an LXMF address.
- The prototype can send and receive text messages over Reticulum.
- LXMF provides end-to-end encrypted delivery; the app does not relay message
  bodies through an Urban Alchemy web server.
- A normal Wi-Fi connection is enough for a first two-device test when both
  devices can see the same Reticulum network. Radio hardware is not required.
- This transport identity is not yet cryptographically bound to a UA persona.
  That binding, contact trust UI, durable UA conversation history, and a
  graphical UA client are subsequent work.

## Install

Python 3.12+ is recommended.

From the repository:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[mesh]"
```

Or install the transport packages directly:

```powershell
python -m pip install rns lxmf
```

## Start the UA messenger

```powershell
python scripts\ua_messenger.py --name "UA Test"
```

The first run creates a persistent Reticulum identity under
`%USERPROFILE%\.urban-alchemy\messenger\` and prints your 32-character LXMF
address. Do not share the private identity file. Share only the printed address.

Keep the terminal open to receive messages. Enter:

```text
/to RECIPIENT_32_HEX_ADDRESS
Hello from Urban Alchemy!
/announce
/quit
```

The recipient can reply to the address printed by your instance.

## Android live test with Sideband

1. Install **Sideband** on Android from its official distribution channel.
2. Create a new LXMF identity in Sideband and open its identity/address details.
3. Put the Sideband LXMF address into the UA terminal with `/to ADDRESS`.
4. Send a message from UA; reply from Sideband.
5. Then send a message from Sideband to the UA address printed in the terminal.

For the simplest first test, put the Windows computer and Android phone on the
same Wi-Fi network. Reticulum's default local discovery can connect peers on a
shared LAN. On Windows, allow Python through the firewall on **Private**
networks if prompted. Do not expose the development FastAPI API or open
unnecessary public ports.

If the devices are not on the same LAN, they must be connected to a common
Reticulum network through configured interfaces or a reachable transport node;
ordinary internet access alone does not guarantee that two isolated Reticulum
instances can find each other.

## Troubleshooting

- Run `/announce` on both sides, then retry.
- Check that the address is exactly 32 hexadecimal characters.
- Keep both apps open for the first direct-delivery test.
- If path discovery times out, confirm both devices are on the same Wi-Fi and
  that the Windows firewall permits Python on the Private network profile.
- The first prototype does not configure a propagation node, so offline
  store-and-forward delivery is not yet part of this test.

## Safety and scope

This is an experimental messaging prototype, not a secure production messenger.
LXMF is actively developed and its upstream project describes it as beta
software. Do not use it for sensitive communications or treat an address as
proof of a person's real-world identity. The prototype has no account recovery,
blocking/reporting UI, contact verification UI, or production key custody.
