package campusnet.api;

import java.net.SocketAddress;

/**
 * Represents one connected TCP client on the chat channel.
 * All methods are called from the selector thread only.
 *
 * send()        — encodes and queues a message; never blocks.
 * sendAndClose()— queues a final message, then closes after the outbox drains.
 * close()       — closes immediately; safe to call multiple times.
 *
 * FROZEN — do not change without following the contract change procedure.
 */
public interface Connection {
    /** Unique connection ID assigned at accept time. */
    long id();

    /** Remote address of this TCP connection. */
    SocketAddress remoteAddress();

    /** The session (auth state) for this connection. */
    Session session();

    /** Encode payload as a frame and queue it for sending. Never blocks. */
    void send(String payload);

    /** Queue a final message, then close the connection once the outbox drains. */
    void sendAndClose(String payload);

    /** Close the connection immediately with a reason for logging. Idempotent. */
    void close(String reason);
}
