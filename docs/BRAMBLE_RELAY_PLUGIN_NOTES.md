# Bramble relay plugin: source audit

This document records API contracts verified directly in the `kinodoc/briar` source tree. It is an implementation note, not a claim that a relay transport has already been implemented.

## Verified public transport contracts

`bramble-api/.../plugin/duplex/DuplexPlugin.java` requires a relay plugin to implement:
- `createConnection(TransportProperties)` for outgoing peer connections;
- `supportsKeyAgreement()`, `createKeyAgreementListener()`, and `createKeyAgreementConnection()`;
- `supportsRendezvous()` and `createRendezvousEndpoint(KeyMaterialSource, boolean, ConnectionHandler)`.

`DuplexPluginFactory` extends `PluginFactory<DuplexPlugin>`. The plugin lifecycle is defined by `Plugin`: `start()`, `stop()`, state, polling interval, and `poll(...)`.

`DuplexTransportConnection` exposes separate `TransportConnectionReader` and `TransportConnectionWriter` objects plus remote transport properties. The reader and writer expose input/output streams and independent `dispose(...)` methods. The reader's `dispose(exception, recognised)` semantics matter: an exception or disposal of both halves closes a duplex connection.

`ConnectionHandler.handleConnection()` is the callback for delivering an accepted duplex connection to Bramble. The relay plugin must not parse or decrypt Bramble application messages.

## Tor implementation behavior that must be replaced

The Android `AppModule` currently provides `PluginConfig.getDuplexFactories()` as Bluetooth, Tor, and LAN. The replacement point is this list: the relay factory must be added while Bluetooth and LAN remain registered.

The Android module also provides Tor-specific directory, SOCKS port, and control port dependencies. The Tor factory constructor depends on these values, `LocationUtils`, `CircumventionProvider`, `TorWrapper`, battery/network state, and a Tor socket factory. These dependencies are not relay dependencies and must not be carried into the new factory.

`TorPlugin.createConnection()` reads the remote onion address from transport properties, validates its format, opens a socket, and returns a `TorTransportConnection`. A relay implementation should instead validate a stable relay peer identifier and establish an authenticated relay session.

`TorPlugin.createRendezvousEndpoint()` obtains two 32-byte seeds from `KeyMaterialSource`, assigns Alice/Bob seeds according to the `alice` flag, derives local and remote rendezvous identities, binds a temporary local listener, and publishes a temporary Tor hidden service. Its returned endpoint exposes remote transport properties and removes the temporary hidden service plus listener on `close()`.

The relay equivalent therefore needs two distinct, deterministic rendezvous aliases from the shared key material, an authenticated server-side registration/lookup lifecycle, and a close operation that revokes the local alias and tears down the listener/session. Alias derivation must be cryptographically domain-separated and must not expose raw rendezvous key material.

## Relay session requirements

The relay protocol must carry a reliable ordered byte stream over the network connection. The initial framing proposal is:

- OPEN: create a session with a random, collision-resistant session ID and bind it to authenticated peer identities;
- DATA: carry a bounded byte payload and a monotonically increasing sequence number for the sending direction;
- ACK: acknowledge a specific contiguous sequence number, with semantics that distinguish relay persistence from remote endpoint consumption;
- CLOSE: close one direction or the whole session with an explicit reason.

Each direction needs independent ordering state. The implementation must define duplicate handling, gap detection, retransmission, bounded in-flight bytes, backpressure, frame limits, idle timeout, reconnect behavior, and cleanup after half-close. A simple newline-delimited JSON packet queue is not sufficient to implement stream semantics.

## Security requirements

- Authenticate the client as the owner of the claimed stable relay identity using proof of possession of its private key; a node ID plus a supplied public key is not proof of ownership.
- Use authenticated TLS with certificate validation/pinning appropriate to the deployment and a defined certificate rotation strategy.
- Authenticate rendezvous alias registration using the shared rendezvous secret without sending that secret to the relay.
- Make aliases unpredictable, short-lived, single-purpose, revocable, and resistant to enumeration.
- Keep private keys and rendezvous key material out of transport properties, logs, crash reports, and server storage.
- Treat the relay as untrusted for message confidentiality and integrity. Bramble's existing end-to-end protocol remains authoritative.

## Integration order

1. Implement and unit-test protocol framing and session state independently of Bramble.
2. Implement a relay client with authenticated identity registration and deterministic shutdown.
3. Implement the Bramble connection reader/writer adapter and verify half-close/disposal behavior.
4. Implement ordinary peer connections and delivery through `ConnectionHandler`.
5. Implement rendezvous endpoint creation, remote properties, alias revocation, and expiry.
6. Add the factory to Android dependency injection while preserving Bluetooth and LAN.
7. Audit mailbox, settings/UI, Java/headless plugins, dependency declarations, and tests before removing Tor.

## Not yet resolved

- Exact transport-property key and `TransportId` allocation for the new relay transport.
- Whether relay supports Bramble key agreement directly; the initial design may report it unsupported if contact introduction is fully covered by rendezvous.
- How mailbox reachability and mailbox delivery should work without Tor. This must be explicitly redesigned rather than silently mapped to relay availability.
- Whether Java/headless targets remain supported in this fork and, if so, which relay client implementation they require.
- Final relay wire format, cryptographic suite, persistence/queue policy, and reconnect guarantees.