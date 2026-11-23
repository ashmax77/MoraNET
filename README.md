# MoraNET — CampusNet NIO

A multi-service campus networking system built with **Java 17**, **Maven**, and **JUnit 5**, using **Java NIO** exclusively for all network I/O.

The system implements a reactive, event-driven chat server, a token-authorised file transfer service, UDP-based LAN discovery, and a console client with network diagnostics — all without thread-per-client overhead.

---

## System Architecture

```
 CLIENT JVM (ClientMain)                         SERVER JVM (ServerMain)
 +-----------------------+                       +-------------------------------------------+
 | ChatClient      (M2)  |==== TCP 5000 ======>  | ChatServer (M1)   1 selector thread       |
 | NetInfo         (M2)  |                       |   Dispatcher -> core handlers (M1)        |
 |                       |                       |              -> ext handlers  (M5)        |
 |                       |                       |   ConnectionRegistry, AuthService (M5)    |
 | FileClient      (M4)  |== TCP 5001 + token => | FileServer (M4)   accept thread + pool    |
 |                       |                       |   TokenService (M5), shared, thread-safe  |
 | DiscoveryClient (M3)  |---- UDP 8888 ------->  | DiscoveryService (M3)   1 thread          |
 +-----------------------+                       +-------------------------------------------+
```

Logical topology: star, with the server at the centre. Physical network: any LAN, Wi-Fi hotspot, or loopback.

### Component Overview

| Component | Port | Transport | Core NIO Classes | Owner |
|---|---|---|---|---|
| ChatServer | 5000 | TCP | `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer` | M1 |
| FileServer | 5001 | TCP | `FileChannel`, `SocketChannel` | M4 |
| DiscoveryService | 8888 | UDP | `DatagramChannel` | M3 |
| Auth and Token Services | in-process | — | `InMemoryAuthService`, `InMemoryTokenService` | M5 |
| Client | — | TCP and UDP | `ChatClient`, `FileClient`, `DiscoveryClient`, `NetInfo` | M2, M3, M4 |

---

## Repository Layout

```
campusnet/
  pom.xml
  config/server.properties          Ports, timeouts, limits, demo users
  docs/
    ARCHITECTURE.md                 System architecture and team contract (this repo)
    modules/
      m1-chat-server.md
      m2-client-netinfo.md
      m3-udp-discovery.md
      m4-file-transfer.md
      m5-auth-tokens.md
  src/main/java/campusnet/
    api/                            Frozen shared contracts (M1)
    common/                         Protocol, ErrorCode, FrameCodec, Config (M1)
    server/
      ServerMain.java               Multi-service entry point (M1)
      chat/net/                     EventLoop, Acceptor, NioConnection, Outbox (M1)
      chat/core/                    Dispatcher, ConnectionRegistry, ServerContext (M1)
      chat/handlers/core/           MsgHandler, ListHandler, PingHandler, QuitHandler (M1)
      chat/handlers/ext/            AuthHandler, PrivateHandler, FilePutHandler, FileGetHandler (M5)
      security/                     InMemoryAuthService, InMemoryTokenService (M5)
      file/                         FileServer, StorageService, UploadTask, DownloadTask (M4)
      discovery/                    DiscoveryService (M3)
    client/
      ClientMain.java               Console client entry point (M2)
      chat/                         ChatClient, CommandParser (M2)
      net/                          NetInfo (M2)
      discovery/                    DiscoveryClient (M3)
      file/                         FileClient (M4)
  src/test/java/campusnet/
    testing/                        FakeConnection, FakeServerContext, TestServer, TestClient (M1)
```

---

## Wire Protocol

### Chat Channel — TCP 5000

Every message is a length-prefixed frame:

```
[int32 big-endian length N][N bytes UTF-8 payload]   where 1 <= N <= 4096
```

The payload is a `VERB` optionally followed by a space and arguments.

| Command | Requires | Success | Failure |
|---|---|---|---|
| `AUTH user pass` | unauthenticated | `OK AUTH user ROLE` | `ERR AUTH_FAILED`, `ERR ALREADY_LOGGED_IN` |
| `MSG text` | auth, `CHAT_BROADCAST` | broadcasts `MSG user: text` | `ERR AUTH_REQUIRED` |
| `PRIVATE user text` | auth, `CHAT_PRIVATE` | `PRIVATE from user: text` to target; `OK PRIVATE_SENT` to sender | `ERR NO_SUCH_USER`, `ERR ACCESS_DENIED` |
| `LIST` | auth | `USERS alice,bob` | `ERR AUTH_REQUIRED` |
| `FILE_PUT size name` | auth, `FILE_UPLOAD` | `OK FILE_READY token 5001` | `ERR ACCESS_DENIED`, `ERR FILE_TOO_LARGE` |
| `FILE_GET name` | auth, `FILE_DOWNLOAD` | `OK FILE_READY token 5001` | `ERR ACCESS_DENIED` |
| `PING` | any | `PONG` | — |
| `QUIT` | any | `OK BYE`, then close | — |

Server-initiated: `WELCOME campusnet/1`, `JOIN user`, `LEAVE user`.

### File Channel — TCP 5001

After `OK FILE_READY token port`, the client opens a separate TCP connection and sends one newline-terminated header line:

- **Upload:** `PUT token`, then exactly `size` raw bytes. Server writes `name.part`, verifies the byte count, atomically moves it to `name`, and replies `OK`.
- **Download:** `GET token`. Server replies `OK size`, then exactly `size` raw bytes.

Tokens are 128-bit hex strings, single-use, and expire after `token.ttlSec` seconds.

### Discovery — UDP 8888

The client broadcasts `DISCOVER campusnet/1`. The server replies unicast with `SERVER chatPort filePort`. The client takes the server host from the reply packet's source address. Timeout: 1 second per attempt, 3 attempts, then falls back to manual host entry.

---

## Role-Based Access Control

| Permission | STUDENT | LECTURER | EXTERNAL |
|---|---|---|---|
| `CHAT_BROADCAST` | yes | yes | yes |
| `CHAT_PRIVATE` | yes | yes | no |
| `FILE_UPLOAD` | yes | yes | no |
| `FILE_DOWNLOAD` | yes | yes | no |

---

## Build and Execution

### Prerequisites

- JDK 17 or higher
- Apache Maven 3.8 or higher

### Commands

```bash
# Compile and run all tests (unit + integration)
mvn -B verify

# Build only (skip tests)
mvn -B package -DskipTests

# Run the server with default configuration
java -cp target/classes campusnet.server.ServerMain

# Run the server with a custom configuration file
java -cp target/classes campusnet.server.ServerMain config/server.properties

# Run the client
java -cp target/classes campusnet.client.ClientMain
```

### Docker

The Docker image pins JDK 17 and Maven 3.9.11, eliminating host JDK/Maven version differences.

```bash
# Build and start the server
docker compose up --build

# Build and run the full test suite in the pinned toolchain
docker build --target build -t moranet-build .
docker run --rm moranet-build mvn -B verify

# Stop the server and remove containers
docker compose down
```

The container exposes TCP ports `5000` and `5001` and UDP discovery port `8888`. Uploaded files are kept in the `moranet-storage` Docker volume.

Integration tests are tagged `@Tag("integration")` and run during `verify`. Unit tests do not open sockets.

---

## Configuration

All keys are read from `config/server.properties`. Each key has a hard-coded default, so a missing key never crashes a service.

| Key | Default | Service |
|---|---|---|
| `chat.port` | 5000 | M1 |
| `chat.maxConnections` | 1000 | M1 |
| `chat.authTimeoutSec` | 30 | M1 |
| `chat.idleTimeoutSec` | 120 | M1 |
| `chat.maxOutboundBytes` | 262144 | M1 |
| `file.port` | 5001 | M4, M5 |
| `file.storageDir` | storage | M4 |
| `file.maxBytes` | 52428800 | M4, M5 |
| `file.idleTimeoutSec` | 30 | M4 |
| `discovery.port` | 8888 | M3 |
| `token.ttlSec` | 60 | M5 |
| `user.<name>` | alice, bob, partner (see docs) | M5 |

---

## Architecture Invariants

1. **Single selector thread.** The chat server uses one selector thread for all non-blocking I/O. No thread-per-client, no `java.net.Socket`, no `java.io` streams in the server.
2. **Non-blocking event loop.** No `Thread.sleep`, no blocking queue operations, and no blocking file I/O on the selector thread.
3. **Length-prefix framing.** Never assume one `read()` returns one complete message. Partial reads and coalesced frames are both handled correctly.
4. **Fault isolation.** Each selection key is wrapped in `try/catch`. One bad connection is closed without affecting any other connection or the server process.
5. **No third-party runtime dependencies.** The entire server and client use Java SE 17 APIs only. JUnit 5 is the sole test dependency.

---

## Git Workflow

| Branch | Purpose |
|---|---|
| `main` | Demo and submission releases. Protected; tagged `v1.0-demo`. |
| `dev` | Integration. Protected; PR only, squash merge, CI green. |
| `feature/mN-short-name` | One unit of work per member. Branched from `dev`; deleted after merge. |
| `fix/mN-short-name` | Bug fix on `dev`. Same rules as feature branches. |
| `docs/mN-short-name` | Documentation only. Same rules as feature branches. |

Commit message format: `mN: <imperative summary>`. Rebase on `origin/dev` before every push; never force-push to `dev` or `main`.

---

## Documentation

Full system design, protocol specification, ownership rules, test plan, and milestone schedule are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

Each member maintains a module design document under `docs/modules/mN-*.md`.
