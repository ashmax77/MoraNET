# MoraNET (CampusNet NIO)

A high-performance, non-blocking networked campus communication and file distribution system built in **Java 17+** using **Java NIO (`java.nio`)**, **Maven**, and **JUnit 5**.

---

## 📌 Project Overview

MoraNET demonstrates modern network programming principles over TCP and UDP without thread-per-client overhead. The system implements a reactive event-driven server architecture using non-blocking channels, selectors, custom length-prefixed framing, backpressure protection, and role-based access control.

### Core Features

- **Non-Blocking Chat Server (TCP 5000):** Single `Selector` thread reactor loop managing all concurrent connections using `ServerSocketChannel` and `SocketChannel`.
- **Length-Prefixed Framing:** Strict byte-stream framing (`[int32 big-endian length][UTF-8 payload]`) supporting partial reads/writes, frame validation, and slow-consumer backpressure.
- **LAN Auto-Discovery (UDP 8888):** Zero-configuration peer and server discovery via `DatagramChannel` broadcast packets.
- **High-Throughput File Transfer (TCP 5001):** Secure data-plane transfers authorised via one-time tokens issued by the chat control plane, powered by `FileChannel` (`transferTo` / `transferFrom`).
- **Role-Based Access Control (RBAC):** Tiered permissions separating intranet roles (`STUDENT`, `LECTURER`) from extranet roles (`EXTERNAL`).
- **Client Network Diagnostics:** Automatic local IP and MAC address discovery (`NetworkInterface`, `InetAddress`).

---

## 🏛️ System Architecture

```
 ChatClient (alice)   ──TCP 5000──► ┌────────────────── Server JVM ──────────────────┐
 ChatClient (bob)     ──TCP 5000──► │  ChatServer (1 Selector Reactor Thread)       │
 ChatClient (partner) ──TCP 5000──► │    ConnectionRegistry ─ Dispatcher ─ Handlers  │
                                    │        │                       │               │
                                    │   AuthService             TokenService         │
 FileClient      ──TCP 5001 (Token)─┼►  FileServer (FileChannel, ./storage)          │
 DiscoveryClient ──UDP 8888─────────┼►  DiscoveryService (DatagramChannel)           │
                                    └────────────────────────────────────────────────┘
```

### Module Responsibilities

| Component | Port | Transport | Core NIO Classes | Responsibility |
|---|---|---|---|---|
| **ChatServer & Reactor** | `5000` | TCP | `ServerSocketChannel`, `SocketChannel`, `Selector`, `ByteBuffer` | Event loop, framing, backpressure, connection registry, dispatcher, core handlers |
| **ChatClient & NetInfo** | — | TCP | `SocketChannel`, `NetworkInterface`, `InetAddress` | Console client UI, connection handling, local network interface inspection |
| **Discovery Service** | `8888` | UDP | `DatagramChannel` | UDP LAN broadcast discovery (server announcement & client probing) |
| **FileServer & Client** | `5001` | TCP | `FileChannel`, `SocketChannel` | Token-authorised file upload/download data plane |
| **Security & RBAC** | Internal | — | Handler logic, `TokenService` | Authentication, roles (`STUDENT`, `LECTURER`, `EXTERNAL`), permission matrix, one-time tokens |

---

## 📜 Wire Protocol

### Chat Channel (TCP `5000`)
Every message is framed as:
```
┌──────────────────────────────┬───────────────────────────────────────────┐
│ Length: 4 bytes (Big-Endian) │ Payload: N bytes (UTF-8, 1 <= N <= 4096)  │
└──────────────────────────────┴───────────────────────────────────────────┘
```

#### Commands

| Command | State / Permission Required | Response / Broadcast |
|---|---|---|
| `AUTH <user> <pass>` | Unauthenticated | `OK AUTH <user> <ROLE>` or `ERR AUTH_FAILED` |
| `MSG <text>` | Authenticated + `CHAT_BROADCAST` | Broadcasts `MSG <user>: <text>` to all users |
| `PRIVATE <user> <text>` | Authenticated + `CHAT_PRIVATE` | Relays `PRIVATE from <user>: <text>` to target |
| `LIST` | Authenticated | `USERS <user1>,<user2>,...` |
| `FILE_PUT <size> <name>` | Authenticated + `FILE_UPLOAD` | `OK FILE_READY <token> 5001` |
| `FILE_GET <name>` | Authenticated + `FILE_DOWNLOAD` | `OK FILE_READY <token> 5001` |
| `PING` | Any | `PONG` |
| `QUIT` | Any | `OK BYE`, then closes connection |

*Server-initiated notifications: `WELCOME campusnet/1`, `JOIN <user>`, `LEAVE <user>`.*

### File Channel (TCP `5001`)
- **Upload:** `PUT <token>\n` followed by `<size>` raw file bytes.
- **Download:** `GET <token>\n` receives `OK <size>\n` followed by raw bytes.
- Tokens are single-use, 128-bit hex strings expiring in 60 seconds with strict path sanitization.

---

## 📂 Repository Layout

```
MoraNET/
├── config/
│   └── server.properties                  # Ports, timeouts, limits, demo users
├── src/
│   ├── main/java/campusnet/
│   │   ├── common/                        # Protocol, ErrorCode, FrameCodec, Config
│   │   ├── server/
│   │   │   ├── ServerMain.java            # Multi-service entry point
│   │   │   ├── chat/                      # Reactor, EventLoop, Connection, Dispatcher, Handlers
│   │   │   ├── security/                  # AuthService, Roles, TokenService
│   │   │   ├── file/                      # FileServer & transfer tasks
│   │   │   └── discovery/                 # UDP DiscoveryService
│   │   └── client/                        # ChatClient, NetInfo, DiscoveryClient, FileClient
│   └── test/java/campusnet/               # Unit, integration, and load tests
└── pom.xml
```

---

## 🛠️ Build & Execution

### Prerequisites
- **JDK 17** or higher
- **Apache Maven 3.8+**

### Compile & Run Tests
```bash
# Compile project
mvn clean compile

# Run all unit and integration tests
mvn test

# Package standalone JAR
mvn package
```

### Running the Server
```bash
# Using compiled classes with default configuration
java -cp target/classes campusnet.server.ServerMain

# Or with a custom configuration file
java -cp target/classes campusnet.server.ServerMain config/server.properties
```

---

## 🛡️ Architecture Invariants & Rules

1. **Single Selector Thread:** The chat server uses one selector thread for non-blocking I/O. No thread-per-client and no legacy blocking sockets (`java.net.Socket`).
2. **Never Block the Event Loop:** No `Thread.sleep`, blocking queue operations, or blocking disk I/O on the selector thread.
3. **Framing Completeness:** Never assume one `read()` returns one complete message. Buffers must handle partial reads and multiple packed frames.
4. **Fault Isolation:** Exceptions on individual client keys are caught and contained; one malformed frame or broken pipe must never crash the server.
5. **Zero Third-Party Runtime Dependencies:** Implemented strictly using pure Java SE 17+ APIs (only JUnit 5 is used for testing).
