# Relay transport implementation plan

## Product direction

This fork replaces Briar's Tor-based Internet transport with a transport operated by the project owner, while retaining local transports.

Target connection modes:
- Bluetooth
- Wi-Fi / LAN
- Internet through the project's VPS relay

The application will also be redesigned and extended with voice messages, large-file transfer (including group conversations), and push-to-talk voice channels.

## Non-negotiable constraints

- Do not use Tor as an Internet transport in the finished product.
- Preserve Bluetooth and LAN transports.
- Keep Bramble's end-to-end encryption and peer authentication. The relay must route opaque transport bytes and must not decrypt application messages.
- Do not start, unmask, or expose the existing VPS relay during development.
- Do not run Android builds or Gradle on the Debian laptop. Use GitHub Actions for builds and tests.
- Debian remains reserved for web development and local browser testing.
- Preserve required upstream copyright notices, licenses, and attribution.

## Existing codebase findings

The Android dependency-injection module currently registers Bluetooth, Tor, and LAN duplex plugin factories. Bramble's transport abstraction is based on ordered, full-duplex byte streams (`DuplexPlugin`, `DuplexPluginFactory`, and `DuplexTransportConnection`).

The Tor plugin also implements rendezvous endpoint creation. Replacing only the normal connection factory would therefore leave contact introduction incomplete. The relay transport must implement both ordinary connections and rendezvous.

Tor-related code is not limited to the Android plugin. The repository also contains Tor-specific dependency declarations, SOCKS wiring, Java/headless transport factories, mailbox reachability monitoring, UI state, and tests. These areas must be audited before Tor dependencies can be removed safely.

## Relay architecture

Implement a Bramble `DuplexPluginFactory` backed by a relay client. A normal peer connection must expose ordered input and output streams to Bramble. A relay session should support:

- explicit OPEN, DATA, and CLOSE frames;
- a unique session identifier;
- monotonically increasing per-direction sequence numbers;
- acknowledgements or a bounded in-flight window;
- duplicate suppression and strict in-order delivery;
- bounded frame sizes, queues, and backpressure;
- timeouts, reconnect handling, and deterministic cleanup.

A server-side `accepted` response must not be treated as proof that the remote Bramble endpoint consumed the bytes. Delivery acknowledgement semantics must distinguish relay receipt from remote stream consumption.

### Contact rendezvous

The relay plugin must implement `createRendezvousEndpoint()`. Use short-lived, unpredictable rendezvous aliases derived from Bramble's shared rendezvous key material. The relay must support authenticated alias registration, lookup, routing, revocation, and expiry. Follow the existing Tor plugin's lifecycle contract, but do not reuse Tor-specific address formats or assumptions.

### Identity and relay authentication

The previous MeshMessenger relay prototype used TLS with certificate pinning and a SQLite store-and-forward queue. Its v1 JSON protocol carried application-specific packets and is not a Bramble byte-stream transport; it cannot be reused unchanged.

The prototype's node registration also needs a proof-of-possession step. A claimed node ID and public key alone are insufficient. Add a challenge-response signed by the node's private key (or an equivalent authenticated mechanism). Keep private identity material out of transport properties and logs.

The relay must never become an endpoint for Bramble message decryption. Retain Bramble's native cryptographic authentication and encryption.

## Implementation sequence

1. Establish a reproducible GitHub Actions baseline and verify the current project build.
2. Map the exact Bramble transport interfaces and Android dependency-injection wiring in this fork.
3. Implement relay protocol framing and a testable transport client independently of the VPS.
4. Implement the Bramble duplex plugin and ordinary peer connections.
5. Implement rendezvous endpoint lifecycle and contact introduction.
6. Integrate the relay factory in Android dependency injection while retaining Bluetooth and LAN.
7. Migrate Tor-dependent UI, mailbox reachability, Java/headless modules, tests, and dependency declarations.
8. Add integration tests for ordering, reconnects, backpressure, expiry, authentication, and failure cleanup.
9. Only after tests pass, prepare a separately reviewed VPS deployment change.

## Explicitly out of scope for early commits

- Starting or modifying the live VPS service.
- Removing all Tor code before the replacement transport and mailbox behavior are defined.
- Changing application identity, package identifiers, or signing configuration before a release plan exists.
- Treating a successful compile as proof of relay interoperability or security.