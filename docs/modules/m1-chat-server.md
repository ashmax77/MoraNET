# M1: Chat Server — Module Documentation

**Owner:** Member 1 (Thisaru Ramanayaka)  
**Package:** `campusnet.server.chat.net`, `campusnet.server.chat.core`, `campusnet.server.chat.handlers.core`  
**Milestone coverage:** I0 (skeleton), I1 (NIO reactor), I2 (config-driven hardening)

---

## Purpose

Implements the non-blocking TCP chat server on port 5000. All concurrent clients
are served by a single selector thread with no per-client threads. The server
frames messages with a 4-byte length prefix (FrameCodec) and routes commands
through the Dispatcher to individual handlers.

---

## Class list

| Class | Package | Role |
|---|---|---|
| `ChatServer` | `chat.net` | Binds the port, starts the selector loop thread, implements `Service` |
| `EventLoop` | `chat.net` | The selector loop: accepts, reads, writes, periodic sweep and metrics |
| `Acceptor` | `chat.net` | Accepts new connections, enforces max-connection limit, sends WELCOME |
| `NioConnection` | `chat.net` | Per-client state: 8 KB inbound buffer, Outbox, Session, timestamps |
| `Outbox` | `chat.net` | Per-connection write queue with OP_WRITE toggling and backpressure tracking |
| `Dispatcher` | `chat.core` | Parses verb, checks auth and permission, dispatches to handler |
| `ConnectionRegistryImpl` | `chat.core` | Tracks all/authenticated connections; bind/remove; broadcast; JOIN/LEAVE |
| `DefaultServerContext` | `chat.core` | Wires Config, ConnectionRegistry, AuthService, TokenService together |
| `MsgHandler` | `handlers.core` | Broadcasts `MSG user: text` to all authenticated connections |
| `ListHandler` | `handlers.core` | Replies `USERS alice,bob,...` |
| `PingHandler` | `handlers.core` | Replies `PONG` |
| `QuitHandler` | `handlers.core` | Sends `OK BYE` and closes |

---

## Design decisions

### Single selector thread
All chat state (registry, sessions, inbound buffers) is owned by one thread.
This eliminates all locks on the hot path. Other threads (FileServer pool,
DiscoveryService) pass work to the selector thread via a `ConcurrentLinkedQueue`
followed by `selector.wakeup()`.

### Outbox and OP_WRITE
`channel.write()` can deliver a partial buffer. The Outbox fast-paths small
writes: if the queue is empty, it tries a direct `channel.write()`. Remaining
bytes go into an `ArrayDeque<ByteBuffer>`. OP_WRITE is added only when the
queue is non-empty, and removed the moment it empties — this prevents the
selector from busy-spinning on a writable key.

### Backpressure
When a connection's outbox exceeds `chat.maxOutboundBytes`, the server sends
`ERR SLOW_CONSUMER` and closes it. The other connections are unaffected.

### Timeout tracking with nanoTime
`System.nanoTime()` is used for all intervals (auth timeout, idle timeout,
sweep interval, metrics interval) to avoid sensitivity to system-clock adjustments.

### Auth and idle timeouts
The periodic sweep (every ~1 s) checks:
- Unauthenticated connections older than `chat.authTimeoutSec` seconds → `ERR AUTH_TIMEOUT`.
- Authenticated connections idle for more than `chat.idleTimeoutSec` seconds → `ERR IDLE_TIMEOUT`.

### Config keys
All keys have hard-coded defaults so a missing or empty `server.properties` never crashes the server.

| Key | Default | Meaning |
|---|---|---|
| `chat.port` | 5000 | TCP port to bind |
| `chat.maxConnections` | 100 | Maximum concurrent connections |
| `chat.authTimeoutSec` | 30 | Seconds before unauthenticated connection is dropped |
| `chat.idleTimeoutSec` | 120 | Seconds of inactivity before authenticated connection is dropped |
| `chat.maxOutboundBytes` | 262144 | Outbox size (bytes) before slow-consumer eviction |

---

## How to run the tests

Unit tests only (no sockets):
```
mvn test
```

Unit + integration tests (uses ephemeral ports):
```
mvn verify
```

Integration tests are in `M1ChatServerIT` and tagged `@Tag("integration")`.

---

## Known limits

- The soak test (IT8: 200 clients × 100 messages) is deferred to I4.
- `AuthHandler` and `PrivateHandler` are M5's responsibility; M1 provides the stubs.
- The `/info`, `/put`, `/get`, `/discover` console commands are M2's work.
