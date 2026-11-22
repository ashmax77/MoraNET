# CampusNet NIO — Demo Script

**Version:** 1.0
**Owner:** Member 1 (consolidates and rehearses this script at I4)
**Rehearsal target:** Run twice cleanly before the submission deadline.

> [!IMPORTANT]
> Run all steps on the demo machine before the presentation. Confirm firewall
> allows ports 5000, 5001, and 8888. Use a phone hotspot if campus Wi-Fi blocks UDP broadcast.

---

## Setup

Start the server on one terminal:

```bash
java -cp target/classes campusnet.server.ServerMain
```

Expected output:
```
INFO: ChatServer listening on port 5000
INFO: FileServer listening on port 5001
INFO: DiscoveryService listening on port 8888
```

Open two more terminals for client A (alice) and client B (bob).

---

## Step 1 — Server startup and port display (M1)

**Narrator: Member 1**

Point to the server terminal. Show three service lines:
- `ChatServer` on port 5000
- `FileServer` on port 5001
- `DiscoveryService` on port 8888

Say: "The server uses one NIO selector thread for chat, a thread pool for file transfers, and a single UDP thread for discovery — no thread per client."

---

## Step 2 — Network info (M2)

**Narrator: Member 2**

On client A's terminal:

```bash
java -cp target/classes campusnet.client.ClientMain
# Type: /info
```

Expected: list of network interfaces with name, MAC address (formatted `AA:BB:CC:DD:EE:FF`), IPv4, IPv6, and MTU.

Say: "This uses `NetworkInterface.getNetworkInterfaces()`. It lists every up, non-loopback interface and reads the MAC byte-by-byte."

---

## Step 3 — Discovery (M3, M2)

**Narrator: Member 3**

On client A and client B's terminals, start `ClientMain` without typing a host:

```bash
java -cp target/classes campusnet.client.ClientMain
# No arguments — it auto-discovers
```

Expected: client finds the server IP and ports from the UDP broadcast reply, connects, and shows `WELCOME campusnet/1`.

Say: "The client sends `DISCOVER campusnet/1` as a UDP broadcast. The server replies `SERVER 5000 5001` unicast. The client uses the reply packet's source IP — it never hardcodes an address."

---

## Step 4 — Login and broadcast (M1, M5)

**Narrator: Member 5 (auth), Member 1 (broadcast)**

On client A:
```
/login alice pw1
```
Expected server reply: `OK AUTH alice STUDENT`. All other clients see: `JOIN alice`.

On client B:
```
/login bob pw2
```
Expected: `OK AUTH bob LECTURER`. Client A sees: `JOIN bob`.

On client A:
```
Hello everyone
```
Expected: Both clients see `MSG alice: Hello everyone`.

On client B:
```
/list
```
Expected: `USERS alice,bob`.

---

## Step 5 — Private message and abrupt disconnect (M1, M5)

**Narrator: Member 1**

On client A:
```
/pm bob Secret message
```
Expected: Client B sees `PRIVATE alice bob: Secret message`. Client A sees `OK PRIVATE_SENT`.

Now kill client A's terminal abruptly (Ctrl+C without `/quit`).

Expected: Client B sees `LEAVE alice`.

Say: "The NIO selector detects EOF on the channel. The EventLoop broadcasts LEAVE and removes the connection — no crash, no effect on other clients."

---

## Step 6 — File upload and download (M4, M5)

**Narrator: Member 4 (transfer), Member 5 (token)**

Reconnect client A and log in as alice. On client A:
```
/put path/to/sample.pdf
```
Expected: `OK FILE_READY <token> 5001`.

Client A connects to port 5001, sends `PUT <token>` + raw bytes. Server writes `sample.pdf.part`, verifies byte count, atomically renames to `sample.pdf`. Replies `OK`.

On client B (logged in as bob):
```
/get sample.pdf
```
Expected: `OK FILE_READY <token> 5001`.

Client B connects to port 5001, sends `GET <token>`. Server streams the file. Client B saves it.

**Compare checksums:**

```bash
sha256sum path/to/sample.pdf downloaded/sample.pdf
```

Expected: identical hashes.

Say: "The file transfer uses `FileChannel.transferTo` and `transferFrom` in a loop. The token is consumed exactly once — it cannot be replayed."

---

## Step 7 — EXTERNAL role restrictions (M5)

**Narrator: Member 5**

On a third terminal, log in as partner (EXTERNAL):
```
/login partner pw3
```

Try a private message:
```
/pm alice hello
```
Expected: `ERR ACCESS_DENIED`.

Try a file upload:
```
/put anyfile.txt
```
Expected: `ERR ACCESS_DENIED`.

Say: "The permission matrix in `InMemoryAuthService.allowed()` blocks EXTERNAL users from CHAT_PRIVATE and FILE_UPLOAD. The Dispatcher enforces this before the handler is ever called."

---

## Step 8 — Token replay rejected (M4, M5)

**Narrator: Member 4 and Member 5**

Attempt to reuse the token from Step 6 by connecting directly to port 5001:

```bash
# Using a raw TCP client or telnet:
# Send: PUT <old-token>\n
# Expected response: ERR BAD_TOKEN
```

Say: "The token was atomically removed from the `ConcurrentHashMap` when it was first redeemed. `map.remove(token)` returns null on the second call, so the file server rejects it immediately."

---

## Checklist before the real demo

- [ ] `mvn -B verify` passes completely (all 40+ tests green)
- [ ] Server starts cleanly on the demo machine
- [ ] All three ports (5000, 5001, and 8888) are allowed through the firewall
- [ ] SHA-256 checksums match in Step 6
- [ ] Token replay (Step 8) is rejected cleanly
- [ ] Each member has practiced narrating their own steps
- [ ] Tested on a phone hotspot (campus Wi-Fi may block UDP broadcast)
