# ADR: First direct transport for the Android messenger

- Status: Accepted for implementation planning; hardware validation pending
- Date: 2026-11-16
- Decision owner: Urban Alchemy project

## Context

The messenger must exchange messages directly between nearby Android devices without mobile data, internet, a central server, or a Reticulum node. The first transport should be achievable by a small project and should not dictate the message model or prevent later Bluetooth and Reticulum adapters.

The current development device is one Samsung Android phone. There is no second Android test device available yet, so protocol integration can be scaffolded and unit-tested, but real radio delivery cannot be claimed.

## Options

| Transport | Internet required? | Strengths | Costs and constraints | Assessment |
|---|---:|---|---|---|
| Wi-Fi Direct (Wi-Fi P2P) | No | Infrastructure-free phone-to-phone link; higher throughput than BLE; TCP sockets can carry the same encrypted message envelope as future transports | Android peer discovery/connection lifecycle; runtime Nearby Wi-Fi Devices permission on Android 13+; older versions may require location permission for discovery; OEM behavior and background limits need device testing | Best first direct transport |
| Local Wi-Fi LAN / hotspot | No, if both devices join the same local network | Straightforward TCP/IP; easier to debug; can use an existing router or one phone's hotspot | Depends on a shared access point/hotspot; discovery and hotspot behavior vary; not a direct radio link managed by the app | Useful fallback and developer test mode |
| Bluetooth Low Energy | No | Low power; widely available; good for discovery and short text payloads | GATT roles, MTU/fragmentation, connection state, permissions, scan throttling, background restrictions, and vendor behavior add complexity; throughput lower than Wi-Fi | Add as second transport, not first |

## Decision

Implement Wi-Fi Direct as the first direct-device transport. Use a transport-neutral message envelope and a small authenticated TCP session over the established P2P network. Do not put Wi-Fi P2P concepts in the conversation or persistence layer.

Keep a local Wi-Fi LAN adapter as a later fallback/debug transport. Add BLE as a separate adapter after Wi-Fi Direct has passed two-device acceptance tests. Reticulum/LXMF remains optional and independent.

## Security requirements

- Discoverability and connection acceptance are user-visible and user-controlled.
- Authenticate the peer using its cryptographic identity after the radio link is established; a Wi-Fi P2P connection alone is not identity authentication.
- Encrypt message payloads end-to-end independently of link encryption.
- Use stable message IDs, expiry, replay protection, and recipient acknowledgements.
- Do not mark a message delivered merely because bytes were written to a socket.
- Do not silently relay another person's messages.

## Android implementation notes

- Use Android's `WifiP2pManager` APIs for discovery, connection negotiation, and group information.
- Request `NEARBY_WIFI_DEVICES` at runtime on Android 13+ for Wi-Fi P2P operations that require it; review version-specific requirements and manifest flags against the final target SDK.
- Handle Wi-Fi state changes, permission denial, peer loss, group teardown, process death, and foreground/background restrictions.
- Bind sockets to the P2P network/interface where required; do not assume the default internet network is the P2P route.
- Keep the transport behind a Kotlin interface so it can be replaced or supplemented without changing persistence or UI.

## Validation plan

1. Unit-test the transport-neutral envelope, IDs, expiry, queue, and acknowledgement rules without radios.
2. Verify local identity and conversation persistence across app restart on the single development phone.
3. When a second Android is available, disable mobile data and internet access on both phones, enable Wi-Fi, discover and connect using Wi-Fi Direct, then exchange messages in both directions.
4. Repeat after process restart and with one peer temporarily offline.
5. Record exact Android versions, permissions, OEM behavior, and battery/background limitations.

## Consequences

Wi-Fi Direct is the first radio transport, but the app remains useful as a local conversation store and outbound queue before a second device is available. No claim of live wireless delivery is made until the two-device tests pass.
