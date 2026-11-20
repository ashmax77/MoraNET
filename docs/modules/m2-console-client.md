# Module M2: Console Client and Network Information

## 1. Purpose and Scope
Module M2 delivers the console client experience for CampusNet NIO. It provides:
- The command-line interactive chat client (`ChatClient`), featuring non-blocking read framing and synchronized user output.
- The command parser (`CommandParser`), mapping slash commands and chat messages to wire-level protocol frames.
- Local network diagnostics (`NetInfo`), inspecting physical/virtual network cards and extracting MTU, MAC, and IP configuration.
- Control-plane coordination for file transfers (`/put` and `/get`), bridging chat channel authorization on TCP 5000 with the file data transfer service on TCP 5001.

## 2. Component Inventory
The module consists of the following classes and tests under the package `campusnet.client`:

| Class / Test | Path | Responsibility |
| --- | --- | --- |
| `ChatClient` | `campusnet/client/chat/ChatClient.java` | Core TCP client managing `SocketChannel`, background reader thread, interactive console loop, and file transfer coordination. |
| `CommandParser` | `campusnet/client/chat/CommandParser.java` | Parses input strings into typed `ParsedCommand` records. |
| `NetInfo` | `campusnet/client/net/NetInfo.java` | Inspects local network interfaces via `NetworkInterface` and formats hardware addresses. |
| `ClientMain` | `campusnet/client/ClientMain.java` | Entry point resolving target server via discovery or CLI args. |
| `CommandParserTest` | `campusnet/client/chat/CommandParserTest.java` | Unit tests for command parsing, quotes, invalid syntax, and case-insensitivity. |
| `NetInfoTest` | `campusnet/client/net/NetInfoTest.java` | Unit tests for MAC address hex formatting and interface inspection. |
| `ChatClientIT` | `campusnet/client/chat/ChatClientIT.java` | Integration tests over loopback verifying PING, AUTH, MSG, file tokens, and clean teardown. |

## 3. Design Decisions and Threading Architecture

### 3.1 Dual-Thread Client Model
Console clients must handle two asynchronous streams simultaneously:
1. **Console Input Thread (Main Thread):** Reads lines from `System.in`, evaluates commands via `CommandParser`, encodes frames via `FrameCodec.encode()`, and writes them to the `SocketChannel`.
2. **Inbound Reader Thread (`chat-client-reader`):** Continuously reads bytes from the `SocketChannel` into an allocated `ByteBuffer`. Complete frames decoded by `FrameCodec.decode()` are parsed and displayed cleanly. Incomplete frames are retained via `buf.compact()`.

### 3.2 Output Synchronization
Because incoming broadcast or private messages may arrive while the user is typing, console prints are routed through a synchronized `println` block (`synchronized (out)`) to prevent stream interleaving and garbled characters.

### 3.3 File Transfer Coordination
The chat channel acts as the control plane for file operations:
- When a user issues `/put <path>`, the client validates local file existence and valid naming via `Protocol.isValidFileName()`, generates a `FILE_PUT <size> <name>` frame, and attaches a `CompletableFuture<String>`.
- The reader thread intercepts `OK FILE_READY <token> <port>` and completes the future.
- The client extracts the single-use token and delegates data stream transfer to `FileTransferClient.upload()`.
- `/get <name> [dest]` follows a symmetrical workflow.

### 3.4 Network Information (`NetInfo`)
`NetInfo` queries `NetworkInterface.getNetworkInterfaces()` instead of relying on `InetAddress.getLocalHost()` (which frequently resolves to loopback on macOS and Linux). It safely formats 6-byte hardware MAC addresses as uppercase hex (`AA:BB:CC:DD:EE:FF`) and returns `"n/a"` if the MAC is unavailable.

## 4. How to Run Tests

### Unit Tests
Unit tests run entirely in-memory without opening network sockets:
```bash
mvn test
```

### Integration Tests
Integration tests spin up ephemeral mock sockets on `127.0.0.1` and run during the Maven `verify` lifecycle:
```bash
mvn verify
```

## 5. Known Limitations
- The client currently uses a blocking `SocketChannel` inside dedicated threads (reader + console loop), which is simpler and robust for CLI interaction.
- Color formatting (ANSI escape codes) is omitted for cross-platform portability across various Windows command prompts and Linux terminals.
