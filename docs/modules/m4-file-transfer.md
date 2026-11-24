# Member 4: File Transfer

## Purpose

Member 4 owns the TCP 5001 file data plane and the M4-side transfer client. It provides authorized
streaming uploads and downloads using token grants issued by Member 5.

## Owned Classes

| Class | Responsibility |
|---|---|
| `FileServer` | Opens TCP file channel, accepts connections, dispatches one transfer per worker |
| `StorageService` | Creates the storage root and safely resolves flat file names |
| `UploadTask` | Streams `Op.PUT` grants to `.part`, validates the size, and atomically moves the final file |
| `DownloadTask` | Streams `Op.GET` grants to the connected client |
| `FileClient` | Opens file-channel connections and streams local files in both directions |

## Wire Protocol

The file server accepts one newline-terminated header:

- `PUT <token>` followed by exactly the grant's upload size
- `GET <token>` followed by `OK <size>` and the exact file bytes

The server never accepts a file name or upload size from the file-channel client. Both come from
the one-time `Grant` produced by `TokenService.redeem`.

## Streaming and Safety

- `FileChannel.transferFrom` and `transferTo` run in loops with explicit running counters.
- Uploads use a sibling `.part` file and atomic move.
- Failed or truncated uploads delete their `.part` file.
- Storage names are resolved beneath one flat storage root.
- Absolute paths, separators, `..`, control characters, and names over 255 characters are rejected.
- The server and client close sockets and file channels on every completion path.

## Configuration

| Key | Default | Purpose |
|---|---:|---|
| `file.port` | 5001 | File-server TCP port |
| `file.storageDir` | `storage` | Flat upload/download directory |
| `file.maxBytes` | 52428800 | Maximum accepted upload size |
| `file.idleTimeoutSec` | 30 | Socket idle timeout |

## Tests

```bash
mvn -B -Dtest=StorageServiceTest test
mvn -B -Dit.test=M4UploadDownloadIT verify
mvn -B verify
```

M4 coverage includes path sanitisation, 0-byte and 1-byte round trips, 5-MB SHA-256 round trips,
wrong-operation and replay rejection, traversal rejection, truncation cleanup, and concurrent uploads.
