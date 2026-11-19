package campusnet.api;

import java.io.IOException;

/**
 * Lifecycle interface shared by ChatServer, FileServer, and DiscoveryService.
 * start() must return quickly and run work on its own thread(s).
 * stop() must be safe to call more than once (idempotent).
 * localPort() returns the actual OS-assigned port after start() — used in tests
 * to avoid hardcoding ports (bind to 0 and ask the OS).
 * FROZEN — do not change without following the contract change procedure.
 */
public interface Service {
    /** Short human-readable name, e.g. "chat", "file", "discovery". */
    String name();

    /** Binds the socket and starts the service thread(s). Returns immediately. */
    void start() throws IOException;

    /** Stops all threads and closes all sockets. Safe to call multiple times. */
    void stop();

    /**
     * Returns the actual local port after start() has been called.
     * Useful when the service was bound to port 0 (ephemeral port) in tests.
     */
    int localPort();
}
