# M3: UDP Discovery — Module Documentation

**Owner:** Member 3
**Packages:** `campusnet.server.discovery`, `campusnet.client.discovery`
**Milestone coverage:** I3 (UDP discovery)

---

## 1. Module Purpose

This module implements LAN-based server discovery using UDP broadcast. A client can
locate the CampusNet server on the same network without knowing its IP address. The
server listens for a standard discovery request and replies with its chat and file ports.
The client derives the server's host IP from the reply datagram's source address.

This satisfies Functional Requirement FR6 from `docs/ARCHITECTURE.md` (Section 1.2):

> *UDP discovery of the server on the LAN*

---

## 2. UDP Discovery Architecture

```
CLIENT JVM                                   SERVER JVM
+--------------------------+                 +-----------------------------------+
|  DiscoveryClient         |                 |  DiscoveryService                 |
|  (campusnet.client.      |                 |  (campusnet.server.discovery)     |
|   discovery)             |                 |                                   |
|                          |-- UDP broadcast>|  DatagramChannel (port 8888)      |
|   DatagramChannel +      |  DISCOVER       |  dedicated thread                 |
|   Selector (non-block)   |  campusnet/1    |  rate limiter (10/s per source)   |
|                          |                 |                                   |
|   collect broadcast      |<-- UDP unicast -|  SERVER 5000 5001                 |
|   targets from NICs      |  to sender IP   |  (chat + file ports)              |
|   retry: 3×1 s           |                 |                                   |
|   host from src addr     |                 |  Logs via System.Logger           |
+--------------------------+                 +-----------------------------------+
```

**Protocol (Section 5.3, frozen):**

| Direction | Datagram text | Notes |
|---|---|---|
| Client → Server | `DISCOVER campusnet/1` | Exact text, UTF-8, ≤ 512 bytes |
| Server → Client | `SERVER <chatPort> <filePort>` | Unicast to sender, UTF-8 |

---

## 3. DiscoveryService Responsibilities

### Class: `campusnet.server.discovery.DiscoveryService`

Implements the `Service` interface. Binds a `DatagramChannel` and runs a single
dedicated blocking receive thread.

| Method | Behaviour |
|---|---|
| `name()` | Returns `"discovery"` |
| `start()` | Opens a `DatagramChannel`, sets `SO_REUSEADDR`, binds to `discovery.port` (default 8888; port 0 for tests), spawns the `"discovery-service"` daemon thread |
| `stop()` | Idempotent via `AtomicBoolean`; closes the channel (unblocks `receive()`); interrupts and joins the thread with a 1-second bound |
| `localPort()` | Returns the actual OS-assigned port after `start()` (supports port 0 for integration tests) |

#### Receive Loop

- Runs on the dedicated `"discovery-service"` daemon thread.
- Uses a 513-byte receive buffer to detect oversized datagrams (the 512-byte limit from Section 5.3).
- Calls blocking `DatagramChannel.receive()`.
- Catches `AsynchronousCloseException` / `ClosedChannelException` as the normal stop signal.
- Any other `IOException` is logged at WARNING level; the loop exits safely.

#### Request Validation

The service only acts on a datagram that:
1. Contains 512 bytes or fewer (datagrams filling the full 513-byte buffer are rejected).
2. Decodes to exactly `"DISCOVER campusnet/1"` (UTF-8, trimmed).

All other datagrams are silently discarded without sending a reply.

#### Reply Generation

Port values come from `Config`:

```java
int chatPort = ctx.config().getInt("chat.port", Protocol.DEFAULT_CHAT_PORT);  // default 5000
int filePort = ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT);  // default 5001
```

The response `"SERVER <chatPort> <filePort>"` is sent unicast to the sender's
socket address using `channel.send()`.

#### Logging

Every successfully answered discovery request is logged at `INFO` level using
`System.Logger`. Rate-limited drops are logged at `DEBUG`. No `System.out` is used
in server-side code.

---

## 4. DiscoveryClient Responsibilities

### Class: `campusnet.client.discovery.DiscoveryClient`

Implements `ServerLocator`. Uses a non-blocking `DatagramChannel` registered with
a `Selector` for precise, deadline-based timeout control.

| Method | Behaviour |
|---|---|
| `locate(Duration timeout)` | Sends DISCOVER to all broadcast targets, waits for `SERVER` reply; retries up to 3 times; returns `Optional.empty()` if no server found |

#### Constructor (Frozen)

```java
public DiscoveryClient(int port)
```

---

## 5. Message Request and Response Formats

### Request

```
DISCOVER campusnet/1
```

- Encoding: `StandardCharsets.UTF_8`
- Maximum size: 512 bytes
- Sent as a UDP datagram broadcast

### Response

```
SERVER <chatPort> <filePort>
```

- Encoding: `StandardCharsets.UTF_8`
- Example: `SERVER 5000 5001`
- Port values must be in range 1–65535
- Sent as a UDP unicast datagram to the sender

---

## 6. Network Interface and Broadcast Handling

The client sends discovery requests to three classes of addresses in order:

1. **Global broadcast** — `255.255.255.255:<port>`
   Reaches all UDP listeners on the same broadcast domain.

2. **Directed broadcasts** — one per active, non-loopback network interface
   Obtained via `NetworkInterface.getNetworkInterfaces()` and
   `InterfaceAddress.getBroadcast()`. Only IPv4 addresses with a non-null broadcast
   address are used. Errors enumerating individual interfaces are logged at DEBUG and
   skipped without crashing.

3. **Loopback fallback** — `127.0.0.1:<port>`
   Guarantees discovery works for single-machine demonstrations where `255.255.255.255`
   is routed to a physical interface but the server binds only to loopback.

No IP address is ever hard-coded except the well-known broadcast constant
`255.255.255.255` and the loopback `127.0.0.1`.

> [!IMPORTANT]
> The server host IP is **always** taken from the reply datagram's source address
> (`InetSocketAddress.getAddress().getHostAddress()`), never from the response text
> and never from a constant.

---

## 7. Timeout and Retry Mechanism

```
locate(Duration timeout)
│
├── Deadline = System.nanoTime() + timeout.toNanos()
│
├── Attempt 1: effectiveAttemptNs = min(1 s, remainingNs)
│   ├── sendToAllTargets(...)
│   └── waitForReply(ch, sel, recvBuf, effectiveAttemptNs)
│       └── Selector.select(remainingMs)   ← precise, no Thread.sleep
│
├── Attempt 2 (if remaining time > 0): same
│
└── Attempt 3 (if remaining time > 0): same
    └── return Optional.empty() if no reply received
```

- **Per-attempt timeout:** 1 second, capped by remaining overall time.
- **Overall bound:** 3 attempts × 1 second = 3 seconds ≤ 3.5-second requirement.
- `System.nanoTime()` is used for all deadline calculations (monotonic clock, immune to wall-clock adjustments).
- `Selector.select(timeoutMs)` is used in a loop that checks the deadline on every wakeup, eliminating arbitrary `Thread.sleep()` calls.
- If a valid reply is received on any attempt, the method returns immediately without waiting for further attempts.

---

## 8. Threading and Resource Management

### DiscoveryService

| Resource | Management |
|---|---|
| `DatagramChannel` | Opened in `start()`, closed in `stop()` via try-with-resources logic |
| Receive thread | Daemon thread `"discovery-service"`, started in `start()` |
| Rate-limiter map | `LinkedHashMap` in access-order mode; guarded by `rateLimiterLock` |

The receive thread is the only thread that reads from the channel. `stop()` is
the only writer to the `stopped` flag (`AtomicBoolean`), which is checked before
each receive iteration and after each `IOException`.

### DiscoveryClient

`locate()` uses a try-with-resources block that guarantees both the `DatagramChannel`
and the `Selector` are closed on every exit path (normal, exception, or early return):

```java
try (DatagramChannel ch = DatagramChannel.open();
     Selector sel = Selector.open()) {
    ...
}
```

No threads are created by the client; `locate()` runs entirely on the caller's thread.

---

## 9. Rate Limiting

The service limits replies to **10 per second per source IP address**.

- Uses a `LinkedHashMap` in access-order mode with a `rateLimiterLock` monitor.
- Each entry tracks a `windowStartNs` (nanosecond timestamp via `System.nanoTime()`) and a `count`.
- On every DISCOVER request:
  1. Expired entries (older than 1 second) are removed (memory safety).
  2. If the table is at the 1024-address cap, the least-recently-accessed entry is evicted.
  3. If the source has no bucket or its bucket has expired, a new bucket is created (count = 1 → allow).
  4. If the source's count is below 10, increment and allow.
  5. Otherwise, drop the request silently.

> [!NOTE]
> Rate-limiting correctness is verified by unit tests (pure logic, no sockets). The
> integration test fires 15 rapid packets and verifies the reply count is ≤ 10.

---

## 10. Error Handling

| Scenario | Behaviour |
|---|---|
| Malformed or unrecognised datagram | Silently ignored; no reply sent; service continues |
| Oversized datagram (> 512 bytes) | Silently ignored; the 513-byte buffer detects this |
| `IOException` during `channel.receive()` | Logged at WARNING; receive loop exits |
| `AsynchronousCloseException` / `ClosedChannelException` | Expected shutdown signal; loop exits cleanly |
| `IOException` sending reply | Logged at WARNING; next receive iteration continues |
| `IOException` enumerating NICs (client) | Logged at WARNING; remaining targets are still tried |
| `IOException` sending to a specific broadcast target (client) | Logged at DEBUG; other targets are still tried |
| Non-InetSocketAddress source (client) | Logged at DEBUG; reply ignored |
| Channel open failure in `start()` | `IOException` propagated to caller |

No empty catch blocks are used. `catch (Throwable)` is never used.

---

## 11. Test Coverage

### Unit Tests

#### `DiscoveryServiceTest` (`campusnet.server.discovery`)

| Test method | What is verified |
|---|---|
| `discoverRequestFormat_matchesProtocol` | Request text equals `"DISCOVER campusnet/1"` |
| `serverResponseFormat_correctPorts` | Response text formatted as `"SERVER 5000 5001"` |
| `serverResponseFormat_nonDefaultPorts` | Response parsed correctly with custom ports |
| `invalidRequest_wrongVersion_rejected` | `campusnet/2` does not match the valid request |
| `invalidRequest_missingPayload_rejected` | Bare `DISCOVER` is rejected |
| `invalidRequest_wrongVerb_rejected` | `FIND campusnet/1` is rejected |
| `invalidRequest_empty_rejected` | Empty string is rejected |
| `invalidRequest_randomText_rejected` | Random text is rejected |
| `parseResponse_validPorts_succeeds` | Valid port range parsed correctly |
| `parseResponse_wrongPrefix_fails` | `OK SERVER ...` rejected |
| `parseResponse_nonNumericPort_fails` | `NumberFormatException` on `abc` as chat port |
| `parseResponse_nonNumericFilePort_fails` | `NumberFormatException` on `xyz` as file port |
| `parseResponse_portZero_invalidRange` | Port 0 is invalid |
| `parseResponse_portNegative_invalidRange` | Port -1 is invalid |
| `parseResponse_portOverMax_invalidRange` | Port 65536 is invalid |
| `parseResponse_portBoundaryMin_valid` | Port 1 is valid |
| `parseResponse_portBoundaryMax_valid` | Port 65535 is valid |
| `parseResponse_extraTokens_fails` | Three tokens rejected |
| `parseResponse_onlyOnePort_fails` | One port rejected |
| `parseResponse_emptyAfterPrefix_fails` | `"SERVER "` with no ports rejected |
| `rateLimiter_allowsUpToTenRepliesPerWindow` | 10 requests allowed per second |
| `rateLimiter_blocksEleventhReplyInWindow` | 11th request in window is denied |
| `rateLimiter_resetsAfterWindowExpiry` | Bucket older than 1 second is expired |
| `datagramSizeLimit_exactly512_accepted` | 512-byte datagram accepted |
| `datagramSizeLimit_513bytes_rejected` | 513-byte datagram rejected |

#### `DiscoveryClientTest` (`campusnet.client.discovery`)

| Test method | What is verified |
|---|---|
| `discoverRequestFormat_exactMatch` | Request text equals `"DISCOVER campusnet/1"` |
| `discoverRequestFormat_utf8Encodable` | Request fits within 512-byte limit |
| `parseReply_validDefaultPorts_succeeds` | Ports 5000/5001 parsed correctly |
| `parseReply_validCustomPorts_succeeds` | Ports 7777/8888 parsed correctly |
| `parseReply_portBoundaryMin_succeeds` | Port 1 accepted |
| `parseReply_portBoundaryMax_succeeds` | Port 65535 accepted |
| `parseReply_wrongPrefix_fails` | `REPLY ...` rejected |
| `parseReply_okPrefix_fails` | `OK ...` rejected |
| `parseReply_emptyString_fails` | Empty string rejected |
| `parseReply_randomText_fails` | Random text rejected |
| `parseReply_nonNumericChatPort_fails` | `abc` chat port rejected |
| `parseReply_nonNumericFilePort_fails` | `xyz` file port rejected |
| `parseReply_portZero_fails` | Port 0 rejected |
| `parseReply_chatPortNegative_fails` | Negative chat port rejected |
| `parseReply_filePortTooLarge_fails` | Port 99999 rejected |
| `parseReply_chatPortTooLarge_fails` | Port 99999 rejected |
| `parseReply_extraTokens_fails` | Three tokens rejected |
| `parseReply_onlyOnePort_fails` | One token rejected |
| `parseReply_serverPrefixOnly_fails` | `"SERVER "` with no ports rejected |
| `isValidPort_one_isValid` | Port 1 is valid |
| `isValidPort_maxPort_isValid` | Port 65535 is valid |
| `isValidPort_zero_isInvalid` | Port 0 is invalid |
| `isValidPort_negative_isInvalid` | Port -1 is invalid |
| `isValidPort_overMax_isInvalid` | Port 65536 is invalid |
| `timeoutBound_threeAttemptsOneSecondEach_completeWithin3500ms` | 3 × 1000 ms ≤ 3500 ms |
| `callerTimeout_shorterThanOneAttempt_limitsAttemptDuration` | 500 ms timeout caps attempt |
| `callerTimeout_longerThanOneAttempt_capedAtOneSecond` | 5 s timeout capped at 1 s per attempt |
| `datagramSizeLimit_exactly512_accepted` | 512-byte datagram accepted |
| `datagramSizeLimit_513bytes_rejected` | 513-byte datagram rejected |

### Integration Tests

#### `DiscoveryServiceIT` (`campusnet.server.discovery`)

| Test method | What is verified |
|---|---|
| `start_bindsEphemeralPort_nonZero` | `localPort()` > 0 after `start()` |
| `discover_loopback_returnsCorrectServerPorts` | Valid DISCOVER gets `SERVER 5000 5001` reply |
| `discover_customPorts_returnedInReply` | Custom ports appear in reply |
| `discover_invalidDatagram_serviceIgnoresAndContinues` | Garbage ignored; service still responds |
| `discover_wrongVersion_serviceIgnores` | `campusnet/2` gets no reply |
| `discover_oversizedDatagram_serviceIgnores` | 513-byte datagram gets no reply |
| `stop_idempotent_noException` | `stop()` called twice does not throw |
| `stop_channelClosed_receiveLoopTerminates` | After `stop()`, port becomes unresponsive |
| `localPort_beforeStart_returnsZero` | `localPort()` before `start()` returns 0 |
| `rateLimiter_caps10RepliesPerSecond` | 15 rapid packets produce ≤ 10 replies |

#### `DiscoveryClientIT` (`campusnet.client.discovery`)

| Test method | What is verified |
|---|---|
| `locate_serverOnLoopback_returnsCorrectServerInfo` | IT6: host is loopback, ports match |
| `locate_serverWithCustomPorts_returnsCorrectPorts` | Custom ports 7000/7001 returned |
| `locate_hostFromPacketSource_notHardcoded` | Host is a valid IP address from packet source |
| `locate_noServer_returnsEmptyWithinTimeBound` | `Optional.empty()` returned in ≤ 3500 ms |
| `locate_invalidReplies_returnsEmptyWithinTimeBound` | Garbage replies → empty within 3500 ms |
| `locate_multipleServices_returnsFirst` | First valid reply wins; subsequent ignored |
| `locate_shortCallerTimeout_completesWithinTimeout` | 500 ms timeout respected |

---

## 12. Commands to Run the Tests

### Unit tests only (no sockets)

```bash
mvn test
```

Unit tests are in `DiscoveryServiceTest` and `DiscoveryClientTest`. They are
excluded from the `integration` JUnit 5 group, so they always run in the `test`
phase.

### Unit + integration tests (uses ephemeral ports)

```bash
mvn verify
```

Integration tests (`DiscoveryServiceIT`, `DiscoveryClientIT`) are picked up by
the Maven Failsafe plugin (naming pattern `*IT.java`) and run in the
`integration-test` phase.

### Integration tests only

```bash
mvn failsafe:integration-test failsafe:verify
```

### Run a specific test class

```bash
mvn -pl . -Dtest=DiscoveryServiceTest test
mvn -pl . -Dit.test=DiscoveryServiceIT failsafe:integration-test failsafe:verify
```

---

## 13. Known Limitations

| Limitation | Detail |
|---|---|
| **Campus Wi-Fi blocks UDP broadcast** | Most university Wi-Fi networks disable inter-host broadcast. Demo on a phone hotspot or over loopback. |
| **IPv6 directed broadcast** | UDP broadcast does not apply to IPv6 (which uses multicast instead). The client targets only IPv4 broadcast addresses and the loopback address. |
| **Subnet-local broadcast only** | Directed broadcast reaches only the local subnet; it will not cross router boundaries. |
| **Multiple servers on the same LAN** | The client returns the first valid reply. If multiple CampusNet servers exist, the one that replies first wins. |
| **Firewall blocking UDP 8888** | Local OS firewalls on Windows or macOS may silently drop UDP 8888. Allow the Java process through the firewall before the demo. |
| **NIC enumeration on VPNs** | VPN software can expose virtual NICs with unexpected broadcast addresses. The client includes these but they typically produce no reply. |
| **Rate-limiter reset on restart** | The in-memory rate-limiter resets when the service is restarted. No persistence across JVM restarts. |
| **No multicast** | The stretch goal of multicast heartbeats (Section 8.3) is not implemented; it requires a new protocol verb and the contract change procedure in Section 4.4. |
