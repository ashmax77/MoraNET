# MoraNET: Campus Network Communications System

## Abstract

MoraNET is a Java 17 client-server application that demonstrates communication and
network programming concepts in a campus-style local area network. Its services
include multi-client text chat over TCP, UDP-based server discovery, role-controlled
private messaging, and file transfer over a separate TCP connection. The chat
server uses Java NIO's selector model; uploads and downloads stream data through
file and socket channels. Authentication and one-time transfer tokens provide
application-level access control. A browser dashboard offers an HTTP and
Server-Sent Events (SSE) interface to the same backend services.

This report describes the system's requirements, architecture, design rationale,
member work areas, networking concepts, and test evidence. A fresh `mvn -B verify`
run completed successfully: 150 unit tests passed, and of 46 integration tests,
45 passed while the opt-in ten-minute soak test was skipped. This result supports
the implemented and automatically exercised behavior; it does not establish
physical-network behavior or independently verify the long-duration soak.

## 1. Introduction

Networked applications must handle communication across machines, concurrent
clients, imperfect message boundaries, and failures without allowing one client
to disrupt the whole service. MoraNET was developed as an educational system for
studying these concerns in a practical Java application. The system combines
connection-oriented and connectionless protocols and places several services
behind a shared server process.

The project is intended to demonstrate network programming, not to provide a
production campus messaging or file-hosting service. Its documented
non-functional goals include fault isolation, bounded resource use,
cross-platform operation, and testability. TLS, persistent chat history, and
production credential storage are explicitly out of scope [1].

## 2. Problem Statement, Objectives, and Scope

### 2.1 Problem statement

A campus group needs a simple way for clients on a local network to locate a
server, communicate with one another, and exchange files without manually
coordinating every server address. The system must also distinguish user roles
and restrict selected actions. The project addresses these requirements while
making the underlying transport, framing, concurrency, and access-control
decisions visible for learning and demonstration.

### 2.2 Objectives

MoraNET's objectives are to:

1. Support simultaneous TCP chat clients without creating one chat thread per
   client.
2. Provide authentication, broadcast chat, user listing, private messages, and
   role-based restrictions.
3. Discover a server on a local IPv4 broadcast domain, with a loopback fallback
   for single-machine demonstrations.
4. Transfer files through a separate TCP data connection, using short-lived,
   single-use authorization tokens.
5. Present local network-interface information and provide a browser dashboard
   for exercising backend capabilities.
6. Verify behavior through unit, integration, and end-to-end tests.

### 2.3 Scope and requirements

The project architecture defines these principal functional requirements [1]:

| Requirement | Summary | Primary owner |
|---|---|---|
| FR1 | Serve concurrent TCP clients without a thread per chat client | M1 |
| FR2 | Authenticate users and assign roles | M5, using M1 transport |
| FR3 | Broadcast chat, list users, and announce joins/leaves | M1 |
| FR4 | Provide role-controlled private messages | M5 |
| FR5 | Provide a console client and local IP/MAC information | M2 |
| FR6 | Discover the server over the LAN using UDP | M3 |
| FR7 | Upload/download files with role-bound one-time grants | M4 and M5 |
| FR8 | Enforce STUDENT, LECTURER, and EXTERNAL permissions | M5 |

The implementation is a single Maven project with client and server entry
points. It uses Java SE APIs for runtime networking and JUnit 5 for tests. The
dashboard adds browser access but delegates protocol and permission behavior to
the existing Java components rather than replacing them [1, 2].

## 3. System Architecture

### 3.1 Topology and services

MoraNET uses a logical star topology: clients communicate with a central server.
The server can run on a LAN host or on the same machine as the clients. One
server JVM starts the chat, file-transfer, and discovery services; the web
dashboard is an additional HTTP service.

| Service | Transport and default port | Responsibility |
|---|---|---|
| Chat | TCP 5000 | Framed commands, chat messages, authentication requests, and transfer authorization |
| File transfer | TCP 5001 | Upload and download data streams |
| Discovery | UDP 8888 | LAN discovery request/reply |
| Dashboard | HTTP, default TCP 8080 | Browser API and static interface |

The console client uses chat over TCP and can use discovery and network-interface
diagnostics. File operations are coordinated through chat, then carried out over
the separate file connection. The browser dashboard communicates with the
backend through an embedded JDK HTTP server and SSE [1, 2].

### 3.2 Chat protocol and event loop

The chat protocol uses a four-byte, big-endian length prefix followed by a UTF-8
payload. The payload is a command verb and optional arguments. This framing is
necessary because TCP presents an ordered byte stream, not a sequence of
application messages: one read may return only part of a frame, or several
frames together.

`ChatServer` uses `ServerSocketChannel`, `SocketChannel`, `Selector`,
`SelectionKey`, and `ByteBuffer`. A selector event loop manages chat connections
and dispatches complete frames. Each connection has an outbound queue. Write
readiness is enabled only while queued bytes remain; an excessive queue triggers
slow-consumer handling and closes that connection rather than allowing its
backlog to grow without bound [1, 3].

### 3.3 File-transfer control and data planes

File transfer deliberately separates authorization from file bytes:

1. The authenticated client requests `FILE_PUT` or `FILE_GET` over the chat
   connection.
2. The server checks the role and request, then issues a token bound to the
   user, operation, file name, and (for upload) expected size.
3. The client opens a second TCP connection and presents the token.
4. The file service redeems the token and streams the data.

Uploads stream into a temporary `.part` file, verify the expected byte count,
and then move the completed file into storage. Downloads stream the stored file
to the client. This avoids treating the chat channel as a bulk-data channel and
lets the token service authorize the separate data-plane connection [1, 4, 5].

### 3.4 UDP discovery

The discovery client broadcasts `DISCOVER campusnet/1`. A server that receives
the exact supported request responds by unicast with `SERVER <chatPort>
<filePort>`. The client obtains the server host address from the reply
datagram's source address. It tries broadcast addresses for eligible network
interfaces and also provides a loopback path for local demonstrations.

UDP discovery is local-network discovery, not a routed, internet-wide service.
Routers commonly do not forward broadcast traffic, and campus wireless networks
or host firewalls may block it [1, 6].

### 3.5 Authentication and authorization

The system has `STUDENT`, `LECTURER`, and `EXTERNAL` roles. The documented
permission matrix allows all three roles to broadcast chat; private messaging
and file operations are restricted for EXTERNAL users. Access is enforced by
the backend handlers and dispatcher, not by the dashboard presentation layer.

The in-memory token service creates 128-bit random tokens, applies a time-to-live
and outstanding-token limit, and removes tokens during redemption so they
cannot be reused. Password checks use constant-time comparison. These are
application-level controls; they do not encrypt network traffic. Credentials
and tokens are held in memory, and the project does not claim TLS protection or
production-grade credential storage [1, 5].

### 3.6 Web dashboard

`DashboardServer` is a same-origin HTTP/SSE gateway. It exposes health and
configuration information, discovery, network-interface data, chat sessions,
file operations, and a demo flow. It adapts browser requests to real backend
services, including framed TCP chat, UDP discovery, file transfer, and backend
authorization. This approach gives browsers a web-compatible interface without
reimplementing the raw TCP/UDP protocols in frontend code [2, 7].

## 4. Design Decisions and Rationale

| Decision | Rationale and effect |
|---|---|
| One selector thread for chat | Uses readiness-based I/O to serve many chat connections without a thread per client. Central ownership of chat state reduces synchronization needs in the event loop. |
| Length-prefixed chat frames | Makes application message boundaries explicit over TCP and supports fragmented or coalesced reads. |
| Bounded outbound queues and connection limits | Prevents a slow receiver or excessive connections from consuming unbounded resources. |
| Separate file data connection | Keeps bulk file bytes apart from chat commands and lets the backend authorize each transfer before data is sent. |
| UDP discovery with retries and loopback fallback | Supports address discovery on a local subnet while keeping single-machine testing practical when broadcast is unavailable. |
| Short-lived, one-time, operation-bound tokens | Links the chat authorization step to a specific file operation on the separate data channel and rejects token replay. |
| In-memory credentials/tokens and local storage | Keeps the educational implementation self-contained; it also means state is not persistent and is unsuitable as-is for production. |
| Embedded JDK HTTP/SSE gateway | Enables browser interaction while preserving the existing Java backend as the authority for protocols and permissions. |
| No third-party runtime libraries | Keeps the networking implementation focused on Java SE NIO and standard APIs; JUnit 5 is used for testing. |

## 5. Member Responsibilities and Delivered Work

The responsibility boundaries below follow the architecture ownership matrix and
module documents [1, 4–8]. They describe the work areas and current automated
evidence; they should not be read as a claim that every deployment or acceptance
activity has been independently completed.

| Member | Responsibility | Implemented capabilities | Current evidence and boundary |
|---|---|---|---|
| M1 | Shared contracts and configuration; server bootstrap; chat networking, event loop, dispatcher and core handlers; shared test utilities | Non-blocking TCP chat; framing; broadcast, LIST, PING and QUIT; join/leave notifications; connection and timeout limits; slow-consumer backpressure | Chat server, slow-consumer, and standard soak integration tests passed in this run. The opt-in ten-minute test was skipped, so this run does not verify its long-duration acceptance. |
| M2 | Console client, command parser, client chat behavior, and network information | Console commands and framed client communication; interface IP/MAC diagnostics; calls into discovery and file-transfer client components | Client/parser/network-info unit and integration test classes are present and included in the successful build. A physical client demonstration was not part of this test run. |
| M3 | UDP discovery client and server | Validates discovery datagrams, returns configured ports, uses response source address, and supports retries and loopback fallback | Discovery unit and loopback integration tests ran successfully. This confirms automated loopback behavior, not discovery across campus Wi-Fi or a physical hotspot. |
| M4 | File server, storage service, upload/download tasks, and file client | Streams files over TCP; validates storage names and byte counts; uses temporary upload files; supports download and cleanup behavior | Storage and file-transfer integration tests passed. The end-to-end test also verified a 5 MiB upload/download by comparing SHA-256 digests. |
| M5 | Authentication, role permissions, extended handlers, private messaging, and token service | Authenticates principals; applies RBAC; supports private messages; issues and redeems expiring, single-use file grants | Authentication/token unit tests and access-control/duplicate-login integration tests ran successfully; end-to-end coverage includes EXTERNAL denials and token replay rejection. |

The browser dashboard is implemented in `campusnet.dashboard.DashboardServer`
and documented as an integration layer, rather than being attributed to one of
the M1–M5 ownership areas in the member responsibility matrix [2, 7].

## 6. Networking Concepts Demonstrated

### 6.1 TCP, ports, and application framing

TCP provides a reliable, ordered byte stream between endpoints. It does not
preserve the boundaries of application writes. MoraNET therefore adds explicit
length-prefix framing to chat messages and newline-terminated headers to the
file-transfer protocol. TCP ports identify the application service at a host:
chat and file transfer use different ports and connections.

### 6.2 Java NIO and readiness-based concurrency

Java NIO channels and selectors allow the chat server to monitor multiple
connections for accept, read, and write readiness. This is an event-driven
concurrency model, distinct from assigning each chat connection a dedicated
thread. The file service uses worker tasks for transfers, while discovery has a
separate receive loop. The design illustrates that different workloads can use
different concurrency models within the same application.

### 6.3 UDP datagrams and broadcast scope

UDP sends discrete datagrams without TCP's connection setup or delivery
guarantee. MoraNET uses it for a small discovery request and response, where
low-cost local reachability is more important than a persistent conversation.
IPv4 broadcast is generally limited to a local broadcast domain; it is not a
replacement for DNS or a global service registry. The source address of the
reply is used to identify the responding host.

### 6.4 Control plane and data plane

The chat request that obtains a file grant is the control plane: it identifies
the operation and authorizes it. The separate TCP file connection is the data
plane: it carries the file bytes. A one-time token links the two stages and
demonstrates how services can separate command traffic from bulk data.

### 6.5 Access control, resource limits, and failure isolation

RBAC demonstrates application-layer authorization. Token expiration and
single-use redemption reduce the window and scope of a transfer grant. File-size,
frame-size, connection, timeout, and outbound-queue limits demonstrate resource
management. Per-connection error handling and transfer cleanup aim to isolate
failures so that one malformed or disconnected client does not take down the
whole server.

### 6.6 Interface addressing and browser protocols

`NetworkInterface` and `InetAddress` expose local interface details, such as IP
addresses and MAC addresses; these values are distinct from a remote server's
address. The dashboard adds HTTP request/response semantics for browser actions
and SSE for server-to-browser event delivery, while the gateway continues to use
the backend protocols internally.

## 7. Testing and Results

### 7.1 Verification method

The full Maven lifecycle was run from the project root:

```text
mvn -B verify
```

The command completed with `BUILD SUCCESS`. Maven Surefire ran the unit tests;
Maven Failsafe ran integration tests during `verify` [3].

### 7.2 Current test results

| Test group | Result |
|---|---:|
| Unit tests | 150 passed; 0 failures; 0 errors |
| Integration tests | 46 discovered: 45 passed; 1 skipped; 0 failures; 0 errors |
| Overall | Build succeeded |

The skipped integration test is `M1LongSoakIT`, enabled only when the
`m1.long.soak` system property is set to `true`. Therefore, the successful
default verification run must not be reported as a completed ten-minute soak.
The M1 module document records soak observations, but those observations were
not independently reproduced by this run [4, 9].

### 7.3 End-to-end and module scenarios

The `CampusNetE2EIT` test starts chat, file, and discovery services on
test-selected ports. It exercises UDP discovery, Alice/Bob/EXTERNAL
authentication, PING and LIST, private messaging, EXTERNAL access denials,
5 MiB file upload and download, SHA-256 equality, and rejection of a reused
download token. `DashboardServerIT` separately exercises the browser gateway
against backend services [9, 10].

Module-level integration tests also cover chat behavior and slow-consumer
handling, loopback discovery, file transfer, and authentication/access-control
scenarios [12]. These tests establish automated behavior in the test
environment. They do not prove operation across every operating system,
firewall, Wi-Fi network, or production workload.

### 7.4 Evidence limits

- The ten-minute, 200-client M1 soak is opt-in and was skipped by the command
  above.
- UDP discovery was verified by automated loopback tests; a physical LAN,
  campus Wi-Fi, or phone-hotspot rehearsal is not established by this run.
- The repository's demo rehearsal documentation describes the automated
  loopback flow and separately notes the need for a live hardware/network
  rehearsal [11].
- Docker setup is documented in the README, but Docker image execution was not
  part of this verification command.

## 8. Limitations and Future Work

MoraNET is a demonstrator, not a production service. Its documented and
implementation-level boundaries include:

1. **No transport encryption:** TCP and HTTP connections are not protected by
   TLS in the documented design.
2. **In-memory security state:** credentials, authenticated sessions, and
   transfer tokens do not persist across process restarts. Demo credentials and
   configuration are not a production identity system.
3. **Local file storage:** files are stored under a configured flat directory;
   there is no distributed storage or database-backed metadata layer.
4. **Broadcast reachability:** UDP discovery may fail where firewalls or network
   infrastructure block broadcast. IPv4 broadcast does not cross routers, and
   IPv6 discovery is not implemented.
5. **No persistent chat history:** messages are exchanged live and are not
   presented as a durable record.
6. **Performance evidence is bounded:** default verification includes a
   standard concurrency test but not the opt-in ten-minute endurance test.
7. **Physical demonstration remains environment-dependent:** real interfaces,
   firewall rules, and Wi-Fi policy can differ from loopback integration tests.

Possible future work includes TLS, a production credential store with password
hashing, persistent storage, IPv6-compatible discovery, deployment hardening,
and repeatable physical-network performance testing. These are extensions, not
features claimed as completed in this report.

## 9. Conclusion

MoraNET combines TCP chat, UDP LAN discovery, a separately authorized TCP file
service, role-based access control, and a browser gateway in a compact Java
application. Its design makes several important networking concepts concrete:
TCP stream framing, selector-based concurrency, UDP broadcast scope, port-based
service separation, control/data-plane coordination, and application-level
authorization. The current `mvn -B verify` result provides reproducible
automated evidence for the unit, integration, and end-to-end test coverage
included in the project. The skipped long soak and unverified physical-network
demo remain explicit evidence gaps rather than implied successes.

## References

1. [System architecture, requirements, ownership, protocols, and testing plan](ARCHITECTURE.md).
2. [Dashboard architecture, routes, and security model](DASHBOARD.md).
3. [Build configuration and Maven test lifecycle](../pom.xml).
4. [M1 chat server module documentation](modules/m1-chat-server.md).
5. [M5 authentication, roles, tokens, and private messages](modules/m5-auth-tokens.md).
6. [M3 UDP discovery module documentation](modules/m3-udp-discovery.md).
7. [Project overview and run instructions](../README.md).
8. [M2 console client and network information](modules/m2-console-client.md).
9. [End-to-end integration test](../src/test/java/campusnet/e2e/CampusNetE2EIT.java) and [M1 long-soak test](../src/test/java/campusnet/server/chat/M1LongSoakIT.java).
10. [Dashboard integration test](../src/test/java/campusnet/dashboard/DashboardServerIT.java).
11. [Automated demo rehearsal and physical-demo caveat](DEMO-REHEARSAL.md).
12. Module integration tests: [M1 chat](../src/test/java/campusnet/server/chat/M1ChatServerIT.java), [M1 slow consumer](../src/test/java/campusnet/server/chat/M1SlowConsumerIT.java), [M3 discovery](../src/test/java/campusnet/server/discovery/DiscoveryServiceIT.java), [M4 file transfer](../src/test/java/campusnet/server/file/M4UploadDownloadIT.java), and [M5 access control](../src/test/java/campusnet/server/security/M5AccessControlIT.java).
