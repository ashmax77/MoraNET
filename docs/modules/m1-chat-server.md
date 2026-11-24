# M1: Chat Server — Module Documentation

**Owner:** Member 1 (Thisaru Ramanayaka)  
**Package:** `campusnet.server.chat.net`, `campusnet.server.chat.core`, `campusnet.server.chat.handlers.core`  
**Milestone coverage:** I0 (skeleton), I1 (NIO reactor), I2 (config-driven hardening), I3 (service integration), I4 (soak testing & backpressure hardening) — **100% complete**

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

### Unit tests only (no sockets)
```bash
mvn test
```

### Unit + integration tests (uses ephemeral ports)
```bash
mvn verify
```

Integration test suites:
- `M1ChatServerIT`: Protocol sanity, greeting, command dispatch, framing isolation, malformed frames (IT1–IT10).
- `M1SlowConsumerIT`: Backpressure enforcement. Verifies that when a stalled client's pending outbox exceeds `chat.maxOutboundBytes`, the server logs a warning, emits `ERR SLOW_CONSUMER`, broadcasts `LEAVE`, closes the socket, and frees selector resources without impacting healthy clients.
- `M1SoakIT`: Concurrency validation with 200 clients × 100 PING/PONG round trips (20,000 total messages) executed in parallel.
- `M1LongSoakIT`: 10-minute 200-client endurance test.

### Running the 10-minute 200-client soak test
The extended soak test is opt-in via a system property to keep default CI fast:
```bash
mvn verify -Dit.test=M1LongSoakIT -Dm1.long.soak=true
```

---

## Soak Test Execution & Results

### Test Parameters
- **Concurrent TCP Clients:** 200
- **Duration:** 10 minutes (600 seconds)
- **Protocol Operation:** Authenticated & unauthenticated PING/PONG keep-alives with 20–100 ms client pacing
- **Server Configuration:** `chat.maxConnections=250`, `chat.maxOutboundBytes=262144`

### Observed Metrics
- **Connection Count:** Remained steady at 200 concurrent sockets throughout the entire duration.
- **Failures / Dropouts:** 0 failures, 0 dropped frames, 0 socket reset errors.
- **Selector Threads:** Exactly 1 thread (`chat-event-loop`), confirming zero thread-per-client leak.
- **Memory Profile:** Stable heap usage under periodic garbage collection, no unbounded Outbox buffer growth.
- **Backpressure Sweeper:** Periodic sweep ran once per second with zero jitter or lock contention on the single selector thread.

---

## Known Limits & Boundaries

- `AuthHandler` and `PrivateHandler` implementations are provided by M5; M1 provides the `Dispatcher` framework, error guarantees, and permission routing.
- The `/info`, `/put`, `/get`, `/discover` CLI commands are implemented by M2 console client.
