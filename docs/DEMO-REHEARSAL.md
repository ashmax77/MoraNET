# CampusNet Automated Demo Rehearsal

## Automated run

The complete loopback demo flow is implemented in:

`src/test/java/campusnet/e2e/CampusNetE2EIT.java`

Run it with:

```bash
mvn -B -Dit.test=CampusNetE2EIT verify
```

Verified result: `BUILD SUCCESS`.

Final verification run: `mvn -B verify` passed with 150 unit tests and 44 integration tests
(43 passed, 1 long-soak test skipped until the `-Dm1.long.soak=true` opt-in run).

## Verified demo checks

- Chat, file, and discovery services start on ephemeral loopback ports.
- Discovery returns the actual chat and file ports.
- Alice logs in as `STUDENT`.
- Bob logs in as `LECTURER`.
- Partner logs in as `EXTERNAL`.
- PING/PONG and user LIST work.
- Private message delivery works.
- EXTERNAL private chat and upload are denied.
- A 5-MB file uploads and downloads successfully.
- The downloaded SHA-256 digest equals the uploaded file digest.
- Reusing the download token returns `ERR BAD_TOKEN`.

## Physical demo status

The automated loopback rehearsal records protocol and system behavior. Campus Wi-Fi,
phone-hotspot broadcast behavior, firewall settings, and the presenter terminal still
require a live hardware rehearsal before a physical demo is declared complete.
