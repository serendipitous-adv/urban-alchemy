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

## Android: run the UA prototype in Termux

The prototype can be run directly on Android through Termux; a separate Linux
installation or rooted device is not required. Reticulum's official manual
documents this Termux approach.

1. Install Termux from its official F-Droid listing:
   https://f-droid.org/en/packages/com.termux/
2. Open Termux and install the Android-native prerequisites:

```sh
pkg update && pkg upgrade
pkg install git python python-cryptography
python -m pip install --upgrade pip wheel
```

3. Clone the prototype branch and install its messaging dependencies:

```sh
git clone -b feat/lxmf-live-messenger https://github.com/serendipitous-adv/urban-alchemy.git
cd urban-alchemy/backend
python -m pip install rns lxmf
```

4. Start the UA messenger:

```sh
python scripts/ua_messenger.py --name "UA Android Test"
```

The first launch creates a persistent Reticulum identity under
`~/.urban-alchemy/messenger/` and prints the public LXMF address. Keep the
identity file private; share only the address.

If the `python-cryptography` Termux package is unavailable for your device,
follow the Android fallback build instructions in the official Reticulum
manual before installing `rns` and `lxmf`:
https://reticulum.network/manual/gettingstartedfast.html

Keep Termux in the foreground for the first test and prevent Android battery
optimization from stopping it. Background reliability and Android lifecycle
integration are not yet implemented.

**Important:** Running the UA CLI proves the prototype starts and can create
an identity; it does not by itself prove a message reached another device.
A real exchange needs an independent LXMF endpoint and a shared Reticulum
route. Sideband on the same phone may contend for local Reticulum interfaces;
the easiest independent test is another phone or computer running Sideband
on the same Wi-Fi. We will verify the actual setup rather than assume it works.

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
