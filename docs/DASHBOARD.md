# MoraNET Web Dashboard

## Start

```bash
java -cp target/classes campusnet.server.ServerMain config/server.properties
```

Open `http://localhost:8080` when `dashboard.port=8080`.

With Docker:

```bash
docker compose up --build
```

## Architecture

The browser uses same-origin HTTP and Server-Sent Events. `DashboardServer` is a JDK HTTP gateway that:

- Opens real TCP chat sessions and sends length-prefixed frames.
- Calls `DiscoveryClient` for UDP discovery.
- Calls `FileClient` for uploads and downloads.
- Calls `NetInfo` for interface diagnostics.
- Reads the shared `ServerContext` for configuration, authentication, and tokens.
- Lists and downloads files through `StorageService` paths.

The dashboard does not implement role checks, token logic, protocol framing, UDP discovery, or file
streaming itself. Those responsibilities remain in M1-M5 Java components.

## API

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/health` | Service status |
| GET | `/api/config` | Effective configuration |
| POST | `/api/discovery` | Run UDP discovery |
| GET | `/api/netinfo` | Network interfaces |
| POST | `/api/chat/sessions` | Create a chat session |
| DELETE | `/api/chat/sessions/{id}` | Close a chat session |
| POST | `/api/chat/sessions/{id}/frames` | Send a chat frame |
| GET | `/api/chat/sessions/{id}/events` | Receive SSE frames |
| GET | `/api/files` | List stored files |
| POST | `/api/files/upload` | Multipart upload |
| GET | `/api/files/{name}?sessionId={id}` | Download a stored file |
| POST | `/api/demo/run` | Run discovery, auth, PING, upload, download, and checksum steps |

## Security

- Browser uploads and downloads require an authenticated dashboard chat session.
- M5 permissions remain authoritative for private chat and file operations.
- Full transfer tokens are never printed or returned as dashboard protocol data.
- File names remain flat and are validated through M4 storage rules.
