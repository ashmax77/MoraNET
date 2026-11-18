# CampusNet NIO: System Architecture and Team Contract

**Version 1.0 | lives at `docs/ARCHITECTURE.md` on the `dev` branch**

| Item | Value |
| --- | --- |
| Project | CampusNet NIO: chat, file sharing and peer discovery |
| Stack | Java 17+, Maven, JUnit 5, Java NIO only (no third-party libraries) |
| Team | Members 1 to 5, one module each (section 8) |
| Document owner | Member 1 (architecture lead, owns the skeleton PR) |
| Change control | Sections 4 to 6 are frozen once the skeleton PR merges (section 4.4) |

## 0. How to use this document

- Read sections 1 to 7 and 10 to 11 once. Read your own module in section 8 twice.
- Build only against the contracts (section 6) and the protocol (section 5). Depend on `campusnet.api` and `campusnet.common`, never on another member's implementation classes.
- If something is unclear or wrong, open an issue labelled `architecture` instead of guessing. If it is a contract problem, follow section 4.4.
- The design goal: five people work on five branches in parallel, merge into `dev` in any order, and never edit the same file.

## 1. Purpose, scope and requirements

### 1.1 What we are building

A campus-style client-server system over TCP and UDP. Clients discover the server on the LAN, log in with a role, chat (broadcast and private) and transfer files. The server uses non-blocking Java NIO throughout.

### 1.2 Functional requirements

| ID | Requirement | Module |
| --- | --- | --- |
| FR1 | Server handles many concurrent TCP clients without a thread per client | M1 |
| FR2 | Clients authenticate with username and password and receive a role | M5 (transport by M1) |
| FR3 | Broadcast chat, user list, join and leave notices | M1 |
| FR4 | Private messages between users, subject to role | M5 |
| FR5 | Console client that shows local IP and MAC information | M2 |
| FR6 | UDP discovery of the server on the LAN | M3 |
| FR7 | File upload and download with `FileChannel`, authorised by role-bound one-time tokens | M4 and M5 |
| FR8 | Role-based access control: STUDENT, LECTURER, EXTERNAL | M5 |

### 1.3 Non-functional requirements

- **Robustness:** a failing client never affects other clients or the server process.
- **Bounded resources:** frame size, outbound queue, connection count, file size and token lifetime are all capped.
- **Testability:** every module has unit tests that need no network; integration tests use ephemeral ports.
- **Portability:** runs on Windows, macOS and Linux (LF line endings, no OS-specific paths).
- **Out of scope:** GUI, TLS, persistent chat history, real credential storage.

### 1.4 Individual contribution map (for the report and viva)

| Member | Module | Network concepts | NIO / java.net evidence | Demo evidence |
| --- | --- | --- | --- | --- |
| M1 | Non-blocking chat server | Client-server, TCP, ports, application-protocol framing | `ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey`, `ByteBuffer` | Many clients chatting; a slow client is dropped |
| M2 | Console client and network info | IPv4/IPv6, MAC address, NIC, TCP client | `SocketChannel`, `InetAddress`, `NetworkInterface` | `/info` output; live chat session |
| M3 | UDP discovery | UDP, broadcast, LAN, datagrams | `DatagramChannel`, `InetSocketAddress`, `StandardSocketOptions` | Client finds the server without typing an IP |
| M4 | File transfer | TCP data plane, zero-copy transfer, storage service | `FileChannel.transferTo/transferFrom`, `SocketChannel` | Upload then download; checksums match |
| M5 | Auth, roles, tokens, private messages | Intranet vs extranet access control, application protocol | Handler logic over NIO, `SecureRandom` tokens | EXTERNAL user denied; token reuse rejected |

## 2. Architecture overview

### 2.1 Context diagram

```
 CLIENT JVM (ClientMain)                         SERVER JVM (ServerMain)
 +-----------------------+                       +-------------------------------------------+
 | ChatClient      (M2)  |==== TCP 5000 ======>  | ChatServer (M1)   1 selector thread       |
 | NetInfo         (M2)  |                       |   Dispatcher -> core handlers (M1)        |
 |                       |                       |              -> ext handlers  (M5)       |
 |                       |                       |   ConnectionRegistry, AuthService (M5)    |
 | FileClient      (M4)  |== TCP 5001 + token => | FileServer (M4)   accept thread + pool    |
 |                       |                       |   TokenService (M5), shared, thread-safe  |
 | DiscoveryClient (M3)  |---- UDP 8888 ------>  | DiscoveryService (M3)   1 thread          |
 +-----------------------+                       +-------------------------------------------+
```

Logical topology: star, with the server at the centre. Physical network: any LAN, Wi-Fi hotspot or loopback.

### 2.2 Components and ports

| Component | Port | Transport | Main classes | Owner |
| --- | --- | --- | --- | --- |
| ChatServer | 5000 | TCP | `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer` | M1 |
| FileServer | 5001 | TCP | `FileChannel`, `SocketChannel` | M4 |
| DiscoveryService | 8888 | UDP | `DatagramChannel` | M3 |
| Auth and token services | in-process | n/a | `InMemoryAuthService`, `InMemoryTokenService` | M5 |
| Client | n/a | TCP and UDP | `ChatClient`, `FileClient`, `DiscoveryClient`, `NetInfo` | M2, M3, M4 |

### 2.3 Deployment

One server JVM (`campusnet.server.ServerMain`) hosts three `Service`s: chat, file, discovery. Any number of client JVMs (`campusnet.client.ClientMain`) run on the same machine or on other machines in the LAN. The server and clients share one codebase and one Maven module.

## 3. Repository, build and configuration

### 3.1 Layout and owners

```
campusnet/
  pom.xml                                      M1
  .gitignore  .gitattributes  .editorconfig    M1
  .github/CODEOWNERS, pull_request_template.md, workflows/ci.yml    M1
  config/server.properties                     M1 (defaults for every key)
  docs/ARCHITECTURE.md                         M1 (this file)
  docs/modules/m1-chat-server.md ... m5-auth-tokens.md    each module owner
  docs/demo-script.md                          M1 (final)
  storage/.gitkeep                             M4 (uploaded files are gitignored)
  src/main/java/campusnet/
    api/                                       M1, FROZEN contracts
    common/                                    M1: Protocol, ErrorCode, FrameCodec, PropertiesConfig
    server/ServerMain.java                     M1
    server/chat/net/                           M1: ChatServer, EventLoop, Acceptor, NioConnection, Outbox
    server/chat/core/                          M1: Dispatcher, ConnectionRegistryImpl, DefaultServerContext
    server/chat/handlers/core/                 M1: MsgHandler, ListHandler, PingHandler, QuitHandler
    server/chat/handlers/ext/                  M5: AuthHandler, PrivateHandler, FilePutHandler, FileGetHandler
    server/security/                           M5: InMemoryAuthService, InMemoryTokenService
    server/file/                               M4: FileServer, StorageService, UploadTask, DownloadTask
    server/discovery/                          M3: DiscoveryService
    client/ClientMain.java, client/chat/       M2: ClientMain, ChatClient, CommandParser
    client/net/                                M2: NetInfo
    client/discovery/                          M3: DiscoveryClient
    client/file/                               M4: FileClient
  src/test/java/campusnet/                     mirrors main; same owner as the main package
    testing/                                   M1: FakeConnection, FakeServerContext, TestServer, TestClient
```

### 3.2 Build and run

```
mvn -B verify                                   # compile + all tests (what CI runs)
mvn -B package -DskipTests                      # build only
java -cp target/classes campusnet.server.ServerMain
java -cp target/classes campusnet.client.ClientMain
```

Integration tests are tagged `@Tag("integration")` and run in `verify`. Unit tests must not open sockets.

### 3.3 Configuration keys

All keys are read through the `Config` interface from `config/server.properties`. Defaults are also hard-coded in each reader, so a missing key never crashes a service.

| Key | Default | Used by |
| --- | --- | --- |
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
| `user.<name>` | `alice=pw1,STUDENT`, `bob=pw2,LECTURER`, `partner=pw3,EXTERNAL` | M5 |

## 4. Ownership and conflict-avoidance rules

### 4.1 Golden rules

1. **One owner per path.** Only the owner edits it (table 4.2). Need a change in someone else's file? Open an issue and tag the owner.
2. **Contracts are frozen.** `campusnet.api`, `campusnet.common` (Protocol, ErrorCode, FrameCodec), the protocol in section 5 and the stub constructors in 4.3 change only through the procedure in 4.4.
3. **The skeleton pre-wires everything.** After the skeleton PR (milestone I0), nobody edits `ServerMain`, `ClientMain`, `pom.xml`, `config/server.properties`, `.github/` or `campusnet.api`. You work only inside your own packages, tests and docs.
4. **Replace stubs, do not rename them.** Keep class names, packages and constructor signatures. Rewrite the file body as you like.
5. **New config keys:** read them with a default in your code (`config.getInt(key, default)`) and document them in your module doc. Do not edit `server.properties`; Member 1 consolidates keys at milestone I4.
6. **No new dependencies.** If you think you need one, ask in an issue. The default answer is no.
7. **No cosmetic changes outside your files:** no mass reformatting, import reordering or cross-package renames. Use the repo `.editorconfig` and turn off IDE format-on-save for files you do not own.
8. **Rebase, do not merge.** `git rebase origin/dev` before every push; PRs are squash-merged into `dev` (section 10).
9. **IDE and agent files are never committed:** `.idea/`, `.vscode/`, agent scratch folders, `target/` and uploaded files are covered by the skeleton `.gitignore`.
10. **Line endings:** `.gitattributes` forces LF, so Windows and macOS members never see whole-file diffs.

### 4.2 Ownership matrix

| Path (under `src/main/java/campusnet/` unless noted) | Owner | Everyone else |
| --- | --- | --- |
| `api/`, `common/` | M1 (contract) | read only |
| `server/ServerMain`, `pom.xml`, `config/`, `.github/`, root dotfiles | M1 | read only |
| `server/chat/net/`, `server/chat/core/`, `server/chat/handlers/core/` | M1 | read only |
| `server/chat/handlers/ext/`, `server/security/` | M5 | read only |
| `server/file/`, `client/file/` | M4 | read only |
| `server/discovery/`, `client/discovery/` | M3 | read only |
| `client/ClientMain`, `client/chat/`, `client/net/` | M2 | read only |
| `src/test/java/campusnet/<same package as main>` | same owner as main package | read only |
| `src/test/java/campusnet/testing/` | M1 | use only; request additions by issue |
| `docs/modules/mN-*.md` | Member N | read only |
| `docs/ARCHITECTURE.md` | M1 | PR labelled `architecture` |

### 4.3 Skeleton PR (milestone I0)

Member 1 delivers this in days 0 to 2. Until it merges, other members read the contracts from section 6 and prepare, but do not push code.

Contents:

- Maven project, `.gitignore`, `.gitattributes`, `.editorconfig`, CODEOWNERS, PR template, CI workflow, `config/server.properties`.
- Every type in `campusnet.api` and `campusnet.common` implemented as in section 6 (`FrameCodec` fully implemented and tested).
- `ServerMain` and `ClientMain` fully wired to the stubs below.
- The stub inventory below: every class compiles and runs.
- `campusnet.testing` helpers.
- Exit criterion: `mvn -B verify` is green and `ServerMain` starts all three services.

Stub inventory (names, packages and constructors are frozen):

| Class | Owner | Constructor | Stub behaviour |
| --- | --- | --- | --- |
| `ChatServer` | M1 | `(ServerContext, Dispatcher)` | starts and stops cleanly, accepts nothing yet |
| `Dispatcher` | M1 | `(ServerContext)`, `register(CommandHandler)` | routes by verb |
| `MsgHandler`, `ListHandler`, `PingHandler`, `QuitHandler` | M1 | `()` | reply `ERR NOT_IMPLEMENTED` |
| `AuthHandler`, `PrivateHandler`, `FilePutHandler`, `FileGetHandler` | M5 | `()` | reply `ERR NOT_IMPLEMENTED` |
| `InMemoryAuthService` | M5 | `(Config)` | accepts any user as STUDENT; `allowed` returns true |
| `InMemoryTokenService` | M5 | `(Config)` | `issue` returns `STUB`; `redeem` returns empty |
| `FileServer` | M4 | `(ServerContext)` | `start()` does nothing |
| `DiscoveryService` | M3 | `(ServerContext)` | `start()` does nothing |
| `DiscoveryClient` | M3 | `(int port)` | `locate` returns localhost with default ports |
| `FileClient` | M4 | `()` | throws `UnsupportedOperationException` |
| `ChatClient` | M2 | `(ServerLocator, FileTransferClient)` | prints a banner |
| `NetInfo` | M2 | `()` | prints nothing |

Because every module compiles against stubs, PRs can merge into `dev` in any order.

### 4.4 Contract change procedure

1. Open an issue labelled `contract` with the problem and a concrete proposal.
2. Discuss in the team chat; decide within 24 hours.
3. Member 1 opens one PR labelled `contract` that changes `api` or `common`, the affected sections of this document, and all affected stubs together, so `dev` never breaks.
4. Approval from every owner of an affected module (a convention: CODEOWNERS cannot require all five) and a green CI run.
5. Everyone runs `git rebase origin/dev` immediately after the merge.

Additive, backward-compatible changes (a new enum constant, a new `default` method) use the same path but need only a quick approval.

## 5. Protocol specification (frozen)

### 5.1 Chat channel (TCP 5000)

**Frame:** `[int32 big-endian length N][N bytes UTF-8 payload]` with N between 1 and 4096. The payload is a `VERB`, optionally followed by a space and arguments. The `Dispatcher` splits at the first space; each handler parses its own `args` string. Verbs are upper-case ASCII.

Client to server:

| Message | Needs | Success | Failure |
| --- | --- | --- | --- |
| `AUTH user pass` | not authenticated | `OK AUTH user ROLE` | `ERR AUTH_FAILED`, `ERR ALREADY_LOGGED_IN` |
| `MSG text` | auth, CHAT\_BROADCAST | everyone gets `MSG user: text` | `ERR AUTH_REQUIRED` |
| `PRIVATE user text` | auth, CHAT\_PRIVATE | target gets `PRIVATE from user: text`; sender gets `OK PRIVATE_SENT` | `ERR NO_SUCH_USER`, `ERR ACCESS_DENIED` |
| `LIST` | auth | `USERS alice,bob` | `ERR AUTH_REQUIRED` |
| `FILE_PUT size name` | auth, FILE\_UPLOAD | `OK FILE_READY token 5001` | `ERR ACCESS_DENIED`, `ERR BAD_REQUEST`, `ERR FILE_TOO_LARGE` |
| `FILE_GET name` | auth, FILE\_DOWNLOAD | `OK FILE_READY token 5001` | `ERR ACCESS_DENIED`, `ERR BAD_REQUEST` |
| `PING` | any state | `PONG` | none |
| `QUIT` | any state | `OK BYE`, then close | none |

Server-initiated: `WELCOME campusnet/1`, `JOIN user`, `LEAVE user`.

Before authentication only AUTH, PING and QUIT are accepted; everything else gets `ERR AUTH_REQUIRED`. `FILE_GET` does not check whether the file exists; the file server answers `ERR NOT_FOUND` on port 5001.

**Error codes (enum `ErrorCode`):** AUTH\_REQUIRED, AUTH\_FAILED, AUTH\_TIMEOUT, ALREADY\_LOGGED\_IN, ACCESS\_DENIED, NO\_SUCH\_USER, BAD\_REQUEST, UNKNOWN\_COMMAND, FRAME\_TOO\_LARGE, FILE\_TOO\_LARGE, SLOW\_CONSUMER, IDLE\_TIMEOUT, SERVER\_BUSY, NOT\_IMPLEMENTED, INTERNAL, BAD\_TOKEN, NOT\_FOUND, SIZE\_MISMATCH, TIMEOUT.

**Valid file names** (`Protocol.isValidFileName`, a pure function with no I/O): 1 to 255 characters, no `/`, no backslash, no `..`, no control characters. Flat storage: no sub-folders.

### 5.2 File data channel (TCP 5001)

The chat channel is the control plane; the file transfer is a separate connection. After `OK FILE_READY token port` the client connects to the file port and sends one newline-terminated text line of at most 256 bytes:

- **Upload:** `PUT token`, then exactly `size` raw bytes, where `size` comes from the token's grant, not from the client. The server writes `name.part`, verifies the byte count, atomically moves it to `name` (replacing any existing file) and answers a line `OK`. Errors: `ERR BAD_TOKEN`, `ERR SIZE_MISMATCH`, `ERR TIMEOUT`.
- **Download:** `GET token`. The server answers a line `OK size`, then exactly `size` raw bytes. Errors: `ERR BAD_TOKEN`, `ERR NOT_FOUND`.

Token rules: random 128-bit hex string, one-time (redeeming removes it), expires after `token.ttlSec`, bound to (user, operation, file name, size). The file server sanitises the name again with `Paths.get(name).getFileName()` and checks `normalize().startsWith(storageRoot)`. Timeouts: 10 seconds for the header line, `file.idleTimeoutSec` of inactivity during transfer. Maximum size: `file.maxBytes`.

### 5.3 Discovery (UDP 8888)

- Client sends the datagram `DISCOVER campusnet/1` as broadcast. The server answers only that exact text, with `SERVER <chatPort> <filePort>` unicast to the sender. Datagrams are at most 512 bytes.
- The client takes the server IP from the reply packet's source address, never from a constant, and ignores later replies.
- Targets: `255.255.255.255`, the directed broadcast address of every up, non-loopback interface, and a `127.0.0.1` unicast fallback for single-machine demos. Timeout 1 second per attempt, 3 attempts, then the client falls back to manual host entry.
- University Wi-Fi often blocks broadcast. Plan to demo on a phone hotspot.

## 6. Shared contracts (`campusnet.api`, frozen)

```java
package campusnet.api;

public enum Role { STUDENT, LECTURER, EXTERNAL }
public enum Permission { NONE, CHAT_BROADCAST, CHAT_PRIVATE, FILE_UPLOAD, FILE_DOWNLOAD }
public enum Op { PUT, GET }
public record Principal(String user, Role role) {}
public record Grant(Principal who, Op op, String fileName, long size) {}   // size is 0 for GET
public record ServerInfo(String host, int chatPort, int filePort) {}

public interface Service {                  // chat, file and discovery servers
    String name();
    void start() throws IOException;        // returns quickly; runs its own threads
    void stop();                            // idempotent
    int localPort();                        // actual bound port after start(), for tests
}

public interface Config {
    String get(String key, String def);
    int getInt(String key, int def);
    Path getPath(String key, String def);
}

public final class Session {                // touched only on the chat thread
    public Principal principal();           // null until authenticated
    public boolean authenticated();
    public void authenticate(Principal p);
}

public interface Connection {
    long id();
    SocketAddress remoteAddress();
    Session session();
    void send(String payload);              // queues; never blocks
    void sendAndClose(String payload);      // queue, flush, then close
    void close(String reason);              // idempotent
}

public interface ConnectionRegistry {       // chat thread only
    boolean bind(Connection c, Principal p);        // false if the user is already logged in;
                                                    // on success sets the session and broadcasts JOIN
    Optional<Connection> findByUser(String user);
    List<Connection> authenticated();               // snapshot copy
    void broadcast(String payload);                 // authenticated connections only
}

public interface ServerContext {
    Config config();
    ConnectionRegistry registry();
    AuthService auth();
    TokenService tokens();
}

public interface CommandHandler {
    String verb();                          // upper-case, e.g. MSG
    boolean requiresAuth();
    Permission required();                  // NONE if authentication alone is enough
    void handle(Connection from, String args, ServerContext ctx);   // chat thread: never block
}

public interface AuthService {
    Optional<Principal> authenticate(String user, String pass);
    boolean allowed(Role role, Permission p);
}

public interface TokenService {             // thread-safe: chat thread and file threads
    String issue(Principal who, Op op, String fileName, long size);
    Optional<Grant> redeem(String token);   // one-time: removes the token atomically
}

public interface ServerLocator { Optional<ServerInfo> locate(Duration timeout) throws IOException; }

public interface FileTransferClient {
    void upload(ServerInfo server, String token, Path file) throws IOException;
    void download(ServerInfo server, String token, Path destination) throws IOException;
}
```

`campusnet.common` also freezes: `Protocol` (constants `VERSION = campusnet/1`, default ports 5000, 5001, 8888, `MAX_PAYLOAD = 4096`, `isValidFileName`), `ErrorCode`, and `FrameCodec` (`encode(String)` and `decode(ByteBuffer)`), which server and client both use.

**Behaviour guarantees the `Dispatcher` gives every handler:**

- If `requiresAuth()` is true and the session is not authenticated, the dispatcher replies `ERR AUTH_REQUIRED` and never calls the handler.
- If `required()` is not NONE and `auth().allowed(role, required())` is false, the dispatcher replies `ERR ACCESS_DENIED` and never calls the handler. Handlers therefore do not repeat these checks.
- `IllegalArgumentException` from a handler becomes `ERR BAD_REQUEST`; any other `RuntimeException` is logged and becomes `ERR INTERNAL`. Neither closes the connection.
- Handlers run on the single chat thread, in arrival order per connection.

**Permission matrix** (implemented by M5 in `InMemoryAuthService.allowed`):

| Permission | STUDENT | LECTURER | EXTERNAL |
| --- | --- | --- | --- |
| CHAT\_BROADCAST | yes | yes | yes |
| CHAT\_PRIVATE | yes | yes | no |
| FILE\_UPLOAD | yes | yes | no |
| FILE\_DOWNLOAD | yes | yes | no |

## 7. Runtime behaviour

### 7.1 Sequences

Login and chat:

```
Client                       ChatServer / Dispatcher                       Other clients
  |--- TCP connect ------------>| accept, register OP_READ
  |<-- WELCOME campusnet/1 -----|
  |--- AUTH alice pw1 --------->| AuthHandler -> auth.authenticate -> registry.bind
  |<-- OK AUTH alice STUDENT ---|-- JOIN alice ------------------------------->|
  |--- MSG hello -------------->| MsgHandler -> registry.broadcast
  |<-- MSG alice: hello --------|-- MSG alice: hello ------------------------>|
```

File upload (control plane on 5000, data plane on 5001):

```
Client (M2, M4)          ChatServer (M1, M5)             FileServer (M4)           TokenService (M5)
  |--FILE_PUT 204800 a.pdf->| permission ok? name valid? size ok?
  |                         |--issue(alice, PUT, a.pdf, 204800)------------------->| token T
  |<--OK FILE_READY T 5001--|
  |--connect 5001: 'PUT T' line + 204800 bytes------>| redeem(T) ----------------->| Grant (T consumed)
  |                         |                         | write a.pdf.part, verify size, atomic move
  |<--OK line---------------------------------------|
```

Discovery:

```
Client (M3)                                        DiscoveryService (M3)
  |--UDP broadcast: DISCOVER campusnet/1 --------->| validate text
  |<-- SERVER 5000 5001 (unicast to the sender) ----|
  server host = reply packet source address; ports come from the reply
```

### 7.2 Threading model

| Component | Threads | Rules |
| --- | --- | --- |
| ChatServer | exactly one selector thread | Owns all chat state (registry, sessions, buffers), so it needs no locks. Handlers never block. Other threads hand work over through a `ConcurrentLinkedQueue` followed by `selector.wakeup()`. |
| FileServer | one accept thread plus a cached pool, one task per transfer | Blocking channel I/O is allowed here. Each task closes its channel in a `finally` or try-with-resources. A token is redeemed exactly once. |
| DiscoveryService | one thread | Blocking receive loop. `stop()` closes the channel to unblock it. |
| TokenService | shared by the chat thread and file threads | `ConcurrentHashMap`; redeem uses `remove()`, which is atomic. |
| Client | main thread reads the console; one reader thread reads server frames | The reader completes a `CompletableFuture` for FILE\_PUT and FILE\_GET replies. All console output goes through one synchronised method. |

### 7.3 Failure behaviour

| Failure | Expected behaviour |
| --- | --- |
| Client drops abruptly | Chat: close that connection only and broadcast `LEAVE`. File: abort the transfer and delete the `.part` file. |
| Malformed or oversized frame | `ERR FRAME_TOO_LARGE`, close that connection. |
| Handler throws | `ERR BAD_REQUEST` or `ERR INTERNAL`; the connection stays open. |
| Slow consumer | `ERR SLOW_CONSUMER` once its outbox exceeds `chat.maxOutboundBytes`, then close. |
| Token expired, reused or wrong operation | `ERR BAD_TOKEN` on port 5001; the chat session is unaffected. |
| File server unreachable | The client reports the error and the chat session continues. |
| No discovery reply | `locate` returns empty; the client asks for a host manually. |

## 8. Module specifications

Each module lists what it owns, what it must and must not do, and how it is accepted. Internals go in the owner's `docs/modules/mN-*.md`.

### 8.1 Member 1: chat server core, skeleton, shared infrastructure

- **Owns:** `api/`, `common/`, `ServerMain`, `server/chat/net/`, `server/chat/core/`, `server/chat/handlers/core/`, `campusnet.testing`, build and CI files.
- **Delivers:** the skeleton PR (4.3); the `Selector` reactor; frame decoding with partial reads; `NioConnection` and `Outbox` with OP\_WRITE toggling and 256 KB backpressure; `ConnectionRegistryImpl` (unique users, JOIN and LEAVE, snapshot iteration); `Dispatcher` with the guarantees in section 6; handlers MSG, LIST, PING, QUIT; auth and idle timeouts; connection limit; a metrics log line every 10 seconds.
- **Must:** one selector thread; no blocking calls in the loop; try/catch per selection key; idempotent `close()`; an 8192-byte inbound buffer so one maximal frame (4096 + 4 bytes) always fits.
- **Must not:** contain authentication rules, file logic or UDP logic.
- **Tests:** `FrameCodec` with fragmented, coalesced, empty and oversized input; `Dispatcher` guarantees; `Outbox` partial write then drain; integration for broadcast, abrupt disconnect, slow consumer, oversized frame; 200-client soak.
- **Acceptance:** all of the above pass; the server runs 10 minutes under 200 clients with stable memory and a constant thread count.

### 8.2 Member 2: console client and network information

- **Owns:** `client/ClientMain`, `client/chat/`, `client/net/`.
- **Uses:** `FrameCodec`, `ServerLocator`, `FileTransferClient`, `ServerInfo`.
- **Delivers:**
  - `ClientMain`: locates the server through `ServerLocator` (fallback: command-line `host:port` or a prompt), connects with `SocketChannel`, starts the reader thread.
  - Console commands: plain text sends MSG; `/login user pass`, `/pm user text`, `/list`, `/put path`, `/get name [dest]`, `/discover`, `/info`, `/ping`, `/quit`.
  - `/put path`: sends `FILE_PUT size name`, waits up to 5 seconds for `OK FILE_READY token port`, then calls `FileTransferClient.upload`. `/get` is symmetrical.
  - `NetInfo`: iterates `NetworkInterface.getNetworkInterfaces()`; for each interface that is up and not loopback, prints name, MAC (null-safe, formatted `AA:BB:CC:DD:EE:FF`), IPv4 and IPv6 addresses and MTU. Do not rely on `InetAddress.getLocalHost()` alone: it often resolves to loopback on Linux and macOS.
- **Must:** read frames with a read-fully loop (a `read()` may return part of a frame); print incoming messages without corrupting the line being typed; exit cleanly when the server disconnects.
- **Must not:** implement file transfer or UDP logic; call the interfaces instead.
- **Tests:** command parsing; MAC formatting for null and 6-byte arrays; `ChatClient` against `TestServer`.
- **Acceptance:** every command works against the real server; `/info` shows the correct IP and MAC on the demo machine.
- **Stretch (optional):** a `Selector`-based non-blocking client; PING round-trip time display; automatic reconnect with back-off.

### 8.3 Member 3: UDP discovery

- **Owns:** `server/discovery/`, `client/discovery/`.
- **Uses:** `Config`, `Protocol`, `ServerLocator`, `ServerInfo`.
- **Delivers:** `DiscoveryService` (a `DatagramChannel` bound to `discovery.port` with SO\_REUSEADDR; answers only `DISCOVER campusnet/1` with `SERVER chatPort filePort`; ignores everything else; caps replies per source, for example 10 per second). `DiscoveryClient` implementing `ServerLocator` per section 5.3: SO\_BROADCAST, directed broadcast addresses from `NetworkInterface.getInterfaceAddresses()`, timeout, retries, loopback fallback.
- **Must:** `stop()` closes the channel and ends the thread; never throw on a malformed datagram; log each answered request.
- **Must not:** hard-code any IP address; touch chat or file code.
- **Tests:** message parsing and formatting; service and client over loopback on an ephemeral port (`localPort()`); garbage datagram ignored; no-server path returns `Optional.empty()` within the time bound.
- **Acceptance:** the client finds the server on one machine and across the demo hotspot; with no server it returns empty in under 3.5 seconds.
- **Stretch (optional):** multicast presence heartbeats and a peer list. This needs a new verb, so it goes through the contract procedure (4.4).

### 8.4 Member 4: file transfer

- **Owns:** `server/file/`, `client/file/`.
- **Uses:** `TokenService.redeem`, `Config`, `Protocol.isValidFileName`, `ServerInfo`.
- **Delivers:** `FileServer` (accept thread, cached pool, header line of at most 256 bytes, token redemption); `UploadTask` using `FileChannel.transferFrom` in a loop; `DownloadTask` using `FileChannel.transferTo` in a loop; `StorageService` (resolve, sanitise, create the storage directory); `.part` temp file plus atomic move; size checks, timeouts and cleanup; `FileClient` implementing `FileTransferClient` with progress output.
- **Must:** loop until all bytes are moved, because `transferTo` and `transferFrom` may move fewer than requested; track the position with a running counter, not `fc.size()`; close channels with try-with-resources; delete the `.part` file on any failure; take the file name and size from the token's grant, never from the client.
- **Must not:** contain role logic (that is M5) or call the chat server.
- **Tests:** sanitisation (`../x`, absolute paths, empty and over-long names); round trips of 0 bytes, 1 byte and 5 MB random data; wrong and expired token; truncated upload leaves no file; concurrent uploads of different names.
- **Acceptance:** SHA-256 of the downloaded file equals the original; traversal attempts stay inside the storage directory.
- **Stretch (optional):** resume support; a cap on concurrent transfers.

### 8.5 Member 5: authentication, roles, tokens, private messages

- **Owns:** `server/chat/handlers/ext/`, `server/security/`.
- **Uses:** `ConnectionRegistry`, `Connection`, `Config`, `Protocol`.
- **Delivers:**
  - `InMemoryAuthService`: loads `user.<name>=<password>,<ROLE>` from `Config`; compares passwords in constant time (`MessageDigest.isEqual`); `allowed()` implements the matrix in section 6.
  - `InMemoryTokenService`: 128-bit `SecureRandom` hex tokens in a `ConcurrentHashMap`; lifetime from `token.ttlSec`; `redeem` uses `remove()` so a token works once; a cap on outstanding tokens per user (for example 5); expired entries purged on `issue`.
  - `AuthHandler` (verb AUTH, `requiresAuth` false): authenticate, then `registry.bind`; reply `OK AUTH user ROLE`, or `ERR ALREADY_LOGGED_IN` when `bind` returns false, or `ERR AUTH_FAILED`; close the connection after 5 failed attempts.
  - `PrivateHandler` (CHAT\_PRIVATE): `registry.findByUser`; `ERR NO_SUCH_USER` when absent; deliver `PRIVATE from user: text` and reply `OK PRIVATE_SENT`.
  - `FilePutHandler` (FILE\_UPLOAD): split `args` at the first space into size and name (the name may contain spaces); validate with `Protocol.isValidFileName`; `ERR FILE_TOO_LARGE` when size is over `file.maxBytes`; `tokens.issue`; reply `OK FILE_READY token filePort`.
  - `FileGetHandler` (FILE\_DOWNLOAD): validate the name, issue a token with size 0, reply the same way.
  - An audit log line for every ACCESS\_DENIED with user, role and verb.
- **Must:** keep handlers non-blocking; not repeat checks the `Dispatcher` already does; never log passwords or full tokens.
- **Must not:** touch the file system or sockets.
- **Tests:** all 12 cells of the permission matrix; token one-time use, expiry and concurrent redeem (exactly one winner); handlers with `FakeConnection` and `FakeServerContext`; names with spaces.
- **Acceptance:** EXTERNAL is denied PRIVATE and FILE\_\*; a STUDENT upload token works exactly once; a duplicate login is rejected.
- **Stretch (optional):** per-role file size limits; temporary lockout after repeated failed logins.

## 9. Cross-cutting standards

- **Language:** Java 17, no preview features. No wildcard imports. `.editorconfig`: 4 spaces, LF, 120 columns.
- **Logging:** `System.Logger` in server code; no `System.out` there. INFO for lifecycle, WARNING for recoverable problems, ERROR for the unexpected. Never log passwords; log at most the first 4 characters of a token.
- **Charset:** always `StandardCharsets.UTF_8`, never the platform default.
- **Resources:** try-with-resources; every channel is closed on every path.
- **Errors:** no empty catch blocks, no `catch (Throwable)`. Network `IOException`s are handled at the module boundary and never escape a service thread.
- **Time:** use `System.nanoTime()` for timeouts and intervals.
- **State:** no mutable static fields; constants live in `Protocol`.
- **Naming:** unit tests `<Class>Test`; integration tests `<Module><Scenario>IT`.
- **Docs:** one or two lines of Javadoc on every public class and method of your module. `docs/modules/mN-*.md` has purpose, class list, design decisions, how to run its tests and known limits; the final report reuses it.
- **TODOs:** only as `TODO(mN, #issue)`.

## 10. Git workflow

### 10.1 Branches

| Branch | Purpose | Rules |
| --- | --- | --- |
| `main` | demo and submission releases | Protected. Only a `dev` to `main` PR by Member 1 once the team agrees. Tagged `v1.0-demo`. |
| `dev` | integration | Protected. PR only, squash merge, CI green, no force-push. |
| `feature/mN-short-name` | one unit of work by member N | Branch from the latest `dev`. Short-lived: merge within 1 to 3 days. Delete after merge. |
| `fix/mN-short-name` | bug fix on `dev` | Same rules as feature branches. |
| `docs/mN-short-name` | documentation only | Same rules as feature branches. |

Examples: `feature/m1-selector-loop`, `feature/m3-udp-discovery`, `fix/m4-partial-transfer`.

### 10.2 Daily loop

```
git checkout dev && git pull --ff-only
git checkout -b feature/m4-upload-task
# ... work, commit small and often
git fetch origin && git rebase origin/dev     # before every push and before opening the PR
mvn -B verify                                 # must be green locally
git push -u origin feature/m4-upload-task     # after a later rebase: git push --force-with-lease
```

Force-push only to your own feature branch, never to `dev` or `main`.

### 10.3 Pull request rules

1. Target `dev`. Title format `mN: short imperative summary`.
2. Touch only files you own (4.2). The PR template has a checkbox for this; reviewers reject PRs that edit foreign files.
3. Keep PRs small: ideally under 400 changed lines and one concern. Large work is split into several PRs, which stubs make possible because `dev` always compiles.
4. Include tests. CI must be green. No commented-out code and no debug prints.
5. One approving review from a member other than the author, within 24 hours. Default reviewer rotation, so everyone understands a neighbouring module's interfaces: M1 is reviewed by M2, M2 by M3, M3 by M4, M4 by M5, M5 by M1.
6. Squash-merge. The author deletes the branch.
7. Commit and squash messages: `mN: <imperative summary>`; add a body when the reason is not obvious.

### 10.4 Conflict policy

- **Conflict in a file you do not own:** you edited something you should not have. Drop your change with `git checkout origin/dev -- <path>` and open an issue for the owner.
- **Conflict in a file you own:** someone else edited it. Ask in the team chat who, resolve it together, then discuss how rule 4.1.1 was broken.
- **Conflict in `docs/ARCHITECTURE.md` or `campusnet.api`:** stop and follow section 4.4.

### 10.5 Branch protection (Member 1 sets this up on day 0)

- **`dev`:** require a pull request; require 1 approval; dismiss stale approvals; require the status check `build`; require the branch to be up to date before merging; require linear history; block force pushes and deletion.
- **`main`:** the same, and only Member 1 merges.
- CODEOWNERS (Appendix A) is used to auto-request the owner's review. If a PR touches someone else's path, that owner is requested automatically, which works as an alarm for rule 4.1.1.
- These are GitHub setting names; adapt them if you use GitLab.

## 11. Integration and testing plan

### 11.1 Test layers

| Layer | Where | Rules |
| --- | --- | --- |
| Unit | `src/test/java/campusnet/<same package as main>` | No sockets. No sleeps over 100 ms. Use the fakes in `campusnet.testing`. |
| Module integration | same package, tagged `integration` | Real sockets on port 0 (ephemeral) via `Service.localPort()`. Each test starts and stops its own service. |
| System (end to end) | `src/test/java/campusnet/e2e/` | The whole `ServerMain` on ephemeral ports. Added after milestone I3. |

Integration and end-to-end test files are named `<Module><Scenario>IT.java`, for example `M4UploadDownloadIT`. Each file has exactly one owner, so tests never conflict either.

### 11.2 Cross-module scenarios

| ID | Scenario | Test owner | Modules | Available after |
| --- | --- | --- | --- | --- |
| IT1 | Two clients log in; a broadcast reaches both | M1 | M1, M5, M2 | I1 |
| IT2 | Duplicate login rejected; abrupt disconnect produces LEAVE | M5 | M1, M5 | I1 |
| IT3 | EXTERNAL is denied PRIVATE and FILE\_PUT | M5 | M5, M1 | I1 |
| IT4 | Upload then download is byte-identical | M4 | M4, M5, M2 | I2 |
| IT5 | Reused, expired and wrong-operation tokens rejected on port 5001 | M4 | M4, M5 | I2 |
| IT6 | Discovery returns the server with correct ports | M3 | M3 | I3 |
| IT7 | Slow consumer dropped while others continue; oversized frame closes only the sender | M1 | M1 | I1 |
| IT8 | 200 clients, 100 messages each, nothing lost or reordered per sender | M1 | M1 | I4 |

### 11.3 Continuous integration

The workflow in Appendix C runs `mvn -B verify` on Java 17 for every PR to `dev` and every push to `dev`. A red `dev` stops the line: the author of the last merge fixes or reverts it within a few hours.

### 11.4 End-to-end demo script (owned by M1, rehearsed at I4)

1. Start `ServerMain` and show the three services and their ports (M1).
2. Run `/info` on a client to show IP and MAC (M2).
3. Start two clients that find the server through discovery without typing a host (M3, M2).
4. Log in as alice and bob; send broadcast and private messages (M1, M5).
5. Show `/list`; drop a client abruptly and show the LEAVE notice (M1).
6. Upload a file as alice, download it as bob, compare checksums (M4, M5).
7. Log in as partner: PRIVATE and upload are denied (M5).
8. Replay the same upload token: it is rejected (M4, M5).

Each member narrates their own steps.

## 12. Schedule and milestones

Adjust the day numbers to the real deadline; keep the order and the gates.

| Milestone | Window | Content | Gate |
| --- | --- | --- | --- |
| I0 Skeleton | days 0 to 2 | Skeleton PR (4.3), branch protection, CI | `mvn verify` green on `dev`; everyone rebased |
| I1 Chat and auth | days 3 to 6 | M1 reactor and core handlers; M5 auth and private messages; M2 client basics | IT1, IT2, IT3, IT7 pass |
| I2 Files | days 5 to 9 | M4 file server and client; M5 file handlers and tokens; M2 `/put` and `/get` | IT4, IT5 pass |
| I3 Discovery and info | days 6 to 10 | M3 discovery; M2 `NetInfo` and `/discover` | IT6 passes |
| I4 Hardening | days 10 to 13 | M1 soak test, metrics, config consolidation; all module docs; demo rehearsal | IT8 passes; `docs/modules/` complete |
| Freeze | day 14 | Bug fixes only; PR `dev` to `main`; tag `v1.0-demo` | Demo script runs cleanly twice |

Windows overlap on purpose: after I0, thanks to the stubs, nobody waits for anyone.

## 13. Risks and mitigations

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Skeleton PR is late and blocks everyone | medium | M1 does it first and others only prepare. Fallback: push a minimal skeleton (`api` plus stubs) on day 1 and the rest later. |
| Contract drift | medium | Frozen contracts, the 4.4 procedure, CODEOWNERS alarm on foreign edits. |
| Merge conflicts | low | Ownership, rebase rule, `.gitattributes`. |
| Campus Wi-Fi blocks UDP broadcast | high | Demo on a phone hotspot; loopback fallback in `DiscoveryClient`. |
| OS firewall blocks the ports | medium | Allow Java through the firewall and test on the demo machines by day 10. |
| MAC address unavailable (virtual NIC, VPN) | medium | `NetInfo` lists every interface and prints `n/a` for a null MAC. |
| A member falls behind | medium | Stubs keep `dev` demoable; a neighbour pairs on the blocked module; drop stretch goals first. |
| Large file exhausts memory | low | Stream with `transferTo`/`transferFrom`, never read a whole file; `file.maxBytes` cap. |
| AI-generated code ignores the rules | medium | Paste section 4.1 and the hard rules into the agent's instructions; reviewers check for thread-per-client designs and blocking I/O on the selector thread. |

## 14. Definition of Done

A PR may merge when all of these hold:

- [ ] It touches only paths owned by the author (or the owner approved).
- [ ] `mvn -B verify` is green locally and in CI.
- [ ] New behaviour has unit tests, and the module's integration scenarios still pass.
- [ ] No new dependency; no change to `api`, `common`, `ServerMain`, `pom.xml` or `config/` (unless labelled `contract`).
- [ ] Public classes have Javadoc; no debug prints; no secrets in logs.
- [ ] Rebased on the latest `dev`; one concern; the description says what and why.

A module is done when all its acceptance criteria in section 8 pass, its `docs/modules/mN-*.md` is complete, its demo steps in 11.4 run live, and its author can explain every class without notes.

## Appendix A: CODEOWNERS

Later lines override earlier ones, so the catch-all comes first. Replace `@m1` to `@m5` with each member's handle, and repeat the main-source lines for the matching `src/test/java/...` paths.

```
*                                                      @m1
/src/main/java/campusnet/api/                          @m1
/src/main/java/campusnet/common/                       @m1
/src/main/java/campusnet/server/chat/net/              @m1
/src/main/java/campusnet/server/chat/core/             @m1
/src/main/java/campusnet/server/chat/handlers/core/    @m1
/src/main/java/campusnet/server/chat/handlers/ext/     @m5
/src/main/java/campusnet/server/security/              @m5
/src/main/java/campusnet/server/file/                  @m4
/src/main/java/campusnet/client/file/                  @m4
/src/main/java/campusnet/server/discovery/             @m3
/src/main/java/campusnet/client/discovery/             @m3
/src/main/java/campusnet/client/chat/                  @m2
/src/main/java/campusnet/client/net/                   @m2
/src/test/java/campusnet/testing/                      @m1
/docs/modules/m2-*.md                                  @m2
/docs/modules/m3-*.md                                  @m3
/docs/modules/m4-*.md                                  @m4
/docs/modules/m5-*.md                                  @m5
```

## Appendix B: Pull request template (`.github/pull_request_template.md`)

```
## What and why
<one or two sentences>

## Checklist
- [ ] I touched only files owned by my module (docs/ARCHITECTURE.md, section 4.2)
- [ ] mvn -B verify passes locally
- [ ] Tests added or updated
- [ ] No changes to api/, common/, ServerMain, pom.xml or config/ (or this PR is labelled contract)
- [ ] Rebased on the latest dev

## How to test
<commands or steps>
```

## Appendix C: CI workflow (`.github/workflows/ci.yml`)

The job name `build` is the status check required by branch protection.

```
name: ci
on:
  pull_request:
    branches: [dev]
  push:
    branches: [dev]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: maven
      - run: mvn -B verify
```

## Appendix D: First-day checklist for every member

1. Clone the repo, switch to `dev`, read this document, and note your module in section 8.
2. Wait for the skeleton PR (I0). Until then, prepare your design notes in `docs/modules/mN-*.md` on a `docs/mN-design` branch.
3. After I0: `git pull`, create `feature/mN-...`, replace your first stub, add its test, run `mvn -B verify`, open a PR.
4. Add your handle to CODEOWNERS through Member 1 and confirm your reviewer in the rotation (10.3, rule 5).
