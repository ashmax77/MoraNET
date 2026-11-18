package campusnet.api;

import java.util.List;
import java.util.Optional;

/**
 * Registry of authenticated connections. Chat thread only — no synchronization needed.
 *
 * bind() — atomically associates a username with a connection and broadcasts JOIN.
 *          Returns false (and does nothing) if the username is already taken.
 * broadcast() — sends a message to all authenticated connections as a snapshot.
 *
 * FROZEN — do not change without following the contract change procedure.
 */
public interface ConnectionRegistry {
    /**
     * Bind an authenticated Principal to a Connection.
     * On success: sets session.authenticate(p) and broadcasts "JOIN user" to all others.
     * @return true if the username was free and binding succeeded; false if already in use.
     */
    boolean bind(Connection c, Principal p);

    /** Look up a connection by username. Returns empty if not found or not authenticated. */
    Optional<Connection> findByUser(String user);

    /** Snapshot copy of all authenticated connections. Safe to iterate while connections change. */
    List<Connection> authenticated();

    /** Send a message to every authenticated connection. One failure must not stop the rest. */
    void broadcast(String payload);
}
