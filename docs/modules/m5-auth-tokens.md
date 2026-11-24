# Module 5: Authentication, Roles, Tokens, and Private Messages

**Owner:** Member 5  
**Package Paths:**
- `src/main/java/campusnet/server/security/`
- `src/main/java/campusnet/server/chat/handlers/ext/`
- `src/test/java/campusnet/server/security/`
- `src/test/java/campusnet/server/chat/handlers/ext/`

---

## 1. Purpose and Scope

Module 5 provides the security backbone and extended chat controls for CampusNet NIO:
1. **Authentication & Identity:** User authentication with constant-time password verification against configured credentials.
2. **Role-Based Access Control (RBAC):** Multi-tier permission matrix supporting `STUDENT`, `LECTURER`, and `EXTERNAL` roles across intranet vs. extranet boundaries.
3. **Secure One-Time Tokens:** High-entropy 128-bit `SecureRandom` tokens for authorizing zero-copy file uploads (`PUT`) and downloads (`GET`) across decoupled control and data planes.
4. **Private Messaging:** Direct peer-to-peer chat between authenticated intranet users subject to role authorization.

---

## 2. Component Inventory

### 2.1 Security Services (`campusnet.server.security`)

| Class | Implements | Responsibilities |
| --- | --- | --- |
| `InMemoryAuthService` | `AuthService` | Loads user credentials (`user.<name>=<password>,<ROLE>`) with hardcoded demo fallbacks; constant-time password matching (`MessageDigest.isEqual`); evaluates the 12-cell role permission matrix; audit logs access denials. |
| `InMemoryTokenService` | `TokenService` | Thread-safe generation of 128-bit hex tokens using `SecureRandom`; atomic single-use redemption via `ConcurrentHashMap.remove()`; per-user outstanding token bounding (cap of 5); TTL enforcement and lazy expiry cleanup. |

### 2.2 Extended Chat Command Handlers (`campusnet.server.chat.handlers.ext`)

| Class | Verb | Required Permission | Description |
| --- | --- | --- | --- |
| `AuthHandler` | `AUTH` | `NONE` (`requiresAuth=false`) | Authenticates username/password; binds connection to `Principal` in `ConnectionRegistry`; sends `OK AUTH <user> <ROLE>`; enforces 5-attempt brute-force protection by closing offending connections. |
| `PrivateHandler` | `PRIVATE` | `CHAT_PRIVATE` | Resolves target recipient in `ConnectionRegistry`; relays `PRIVATE from <sender>: <text>` to recipient and replies `OK PRIVATE_SENT` to sender; rejects non-existent targets with `ERR NO_SUCH_USER`. |
| `FilePutHandler` | `FILE_PUT` | `FILE_UPLOAD` | Parses `<size> <name>` (names may contain spaces); validates file name against directory traversal (`Protocol.isValidFileName`); checks size against `file.maxBytes`; issues `PUT` token; replies `OK FILE_READY <token> <filePort>`. |
| `FileGetHandler` | `FILE_GET` | `FILE_DOWNLOAD` | Parses `<name>` (supports spaces); validates file name; issues `GET` token with size 0; replies `OK FILE_READY <token> <filePort>`. |

---

## 3. Permission Matrix

Enforced via `InMemoryAuthService.allowed(Role role, Permission p)`:

| Permission | STUDENT | LECTURER | EXTERNAL |
| --- | :---: | :---: | :---: |
| `CHAT_BROADCAST` | Yes | Yes | Yes |
| `CHAT_PRIVATE` | Yes | Yes | **No** (`ACCESS_DENIED`) |
| `FILE_UPLOAD` | Yes | Yes | **No** (`ACCESS_DENIED`) |
| `FILE_DOWNLOAD` | Yes | Yes | **No** (`ACCESS_DENIED`) |
| `NONE` | Yes | Yes | Yes |

*Audit Logging:* Any denied permission triggers an audit warning log:  
`ACCESS_DENIED: role <ROLE> denied permission <PERMISSION>`.

---

## 4. Key Design Decisions

1. **Constant-Time Verification Against Timing Attacks:**
   - Password comparison uses `MessageDigest.isEqual` to eliminate timing side channels.
   - For non-existent users, a dummy constant-time comparison is executed to prevent username enumeration through response latency discrepancies.

2. **Decoupled Control and Data Planes via One-Time Tokens:**
   - The chat server on TCP port 5000 is strictly control plane. File transfers occur on port 5001.
   - Tokens are 128-bit (16 random bytes formatted as 32 lowercase hex characters), cryptographically generated with `java.security.SecureRandom`.
   - `InMemoryTokenService.redeem()` uses `ConcurrentHashMap.remove(token)` to ensure atomic, single-winner redemption across concurrent worker threads.

3. **Privacy and Masked Logging:**
   - Server code logs through `System.Logger`.
   - Passwords are never logged in any format.
   - Token strings are logged with only their first 4 characters (e.g. `a3f1...`) to preserve auditability without exposing transfer credentials.

4. **Resource Bounding & Rate Limiting:**
   - **Brute-Force Protection:** Connection tracking terminates sockets reaching 5 consecutive failed `AUTH` requests.
   - **Token Caps:** Maximum 5 outstanding active tokens per user (`token.maxPerUser`) to prevent resource exhaustion attacks.
   - **Time-to-Live (TTL):** Tokens expire after `token.ttlSec` (default 60 seconds). Expired entries are automatically purged upon subsequent token issuance.

5. **Non-Blocking Reactor Thread Safety:**
   - Handlers execute on the single reactor thread and never block on I/O.
   - `InMemoryTokenService` is fully synchronized for concurrent access by chat and file-server worker threads.

---

## 5. Verification and Test Suite

### 5.1 Unit Tests

- `campusnet.server.security.InMemoryAuthServiceTest`:
  - Exhaustive verification of all 12 cells in the role permission matrix.
  - Authentication with default demo credentials (`alice`, `bob`, `partner`).
  - Authentication with dynamic properties via `Config`.
  - Rejection of invalid passwords, unknown users, and empty credentials.
- `campusnet.server.security.InMemoryTokenServiceTest`:
  - 128-bit hex format compliance.
  - Strict one-time use semantics.
  - Expiration behavior after configured TTL.
  - Concurrent multi-threaded redemption (10 simultaneous threads, exactly one winner).
  - Per-user token cap enforcement and automatic purging of expired tokens.
- `campusnet.server.chat.handlers.ext.AuthHandlerTest`:
  - Successful authentication and registry binding.
  - Duplicate login rejection on existing sessions and in-use usernames.
  - Connection termination after 5 failed authentication attempts.
- `campusnet.server.chat.handlers.ext.PrivateHandlerTest`:
  - Message relaying to active recipient and delivery confirmation.
  - `ERR NO_SUCH_USER` handling.
- `campusnet.server.chat.handlers.ext.FilePutHandlerTest` & `FileGetHandlerTest`:
  - Token issuance and wire format compliance (`OK FILE_READY <token> <port>`).
  - Support for file names containing spaces (e.g. `Final Project Report 2026.pdf`).
  - Validation guards against directory traversal (`../`) and illegal control characters.
  - Enforcement of maximum upload size bounds (`file.maxBytes`).

### 5.2 Integration Tests

- `campusnet.server.security.M5AccessControlIT` (Scenario IT3):
  - Proves that `EXTERNAL` accounts can broadcast chat messages but are strictly blocked with `ERR ACCESS_DENIED` on `PRIVATE`, `FILE_PUT`, and `FILE_GET`.
- `campusnet.server.security.M5DuplicateLoginIT` (Scenario IT2):
  - Proves that logging in as an already-authenticated user returns `ERR ALREADY_LOGGED_IN`.
  - Proves that upon user disconnect/unbinding, the username is freed and can be bound by a subsequent session.

---

## 6. Known Limitations and Future Extensions

- **Ephemeral Storage:** User credentials and tokens are held in-memory; process restarts flush active sessions and unredeemed tokens.
- **Future Stretch Goals:**
  - Password hashing via Argon2id / bcrypt rather than plaintext in config.
  - Dynamic token quotas and bandwidth allocation based on role tiers (e.g. larger caps for `LECTURER`).
  - IP-based sliding window lockout for brute-force mitigation across reconnections.
