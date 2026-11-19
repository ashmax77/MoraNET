package campusnet.server.chat.net;

import campusnet.api.Connection;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;
import campusnet.common.FrameCodec;
import campusnet.server.chat.core.ConnectionRegistryImpl;
import campusnet.server.chat.core.Dispatcher;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The single-threaded NIO select loop (M1).
 *
 * Responsibilities:
 *  - Drains a task queue before each select so callers on other threads can
 *    safely schedule work on the selector thread (e.g., register new channels).
 *  - Dispatches OP_ACCEPT, OP_READ, and OP_WRITE to the appropriate helpers.
 *  - Runs a periodic sweep (~1 s) to enforce auth timeout, idle timeout, and
 *    backpressure limits.
 *
 * Constraints (architecture §5):
 *  - No {@code Thread.sleep}, no blocking I/O, no blocking queues here.
 *  - Every key failure is isolated; one bad connection must not crash the loop.
 */
public class EventLoop implements Runnable {

    private static final System.Logger LOG = System.getLogger(EventLoop.class.getName());

    /** Max milliseconds a connection may be unauthenticated. */
    private static final long AUTH_TIMEOUT_MS  = 30_000L;
    /** Max milliseconds of inactivity before a connection is dropped. */
    private static final long IDLE_TIMEOUT_MS  = 120_000L;
    /** Outbox size (bytes) above which the connection is considered a slow consumer. */
    private static final long BACKPRESSURE_BYTES = 256 * 1024L;
    /** Period (ms) between sweep passes. */
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    private final ServerContext ctx;
    private final Dispatcher    dispatcher;
    private final Acceptor      acceptor;
    private final Selector      selector;
    private final ConcurrentLinkedQueue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private long lastSweep = 0;

    public EventLoop(ServerContext ctx, Dispatcher dispatcher, Acceptor acceptor, Selector selector) {
        this.ctx        = ctx;
        this.dispatcher = dispatcher;
        this.acceptor   = acceptor;
        this.selector   = selector;
    }

    // -------------------------------------------------------------------------
    // Public API (may be called from any thread)
    // -------------------------------------------------------------------------

    /** Submit a task to run on the selector thread before the next select(). */
    public void submit(Runnable task) {
        taskQueue.add(task);
        selector.wakeup();
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    // -------------------------------------------------------------------------
    // Runnable — selector thread entry point
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        running = true;
        LOG.log(System.Logger.Level.INFO, "EventLoop started on thread {0}", Thread.currentThread().getName());

        while (running) {
            try {
                // 1. Drain any pending submitted tasks.
                drainTaskQueue();

                // 2. Block for up to 1 s waiting for I/O events.
                selector.select(1_000L);

                // 3. Process ready keys.
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) {
                            acceptor.accept(key);
                        } else {
                            NioConnection conn = (NioConnection) key.attachment();
                            if (conn == null) { key.cancel(); continue; }

                            if (key.isReadable())  handleRead(key, conn);
                            if (key.isValid() && key.isWritable()) handleWrite(conn);
                        }
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.WARNING, "Key error: {0}", e.getMessage());
                        safeClose(key);
                    }
                }

                // 4. Periodic sweep.
                long now = System.currentTimeMillis();
                if (now - lastSweep >= SWEEP_INTERVAL_MS) {
                    sweep(now);
                    lastSweep = now;
                }

            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.ERROR, "Selector error: {0}", e.getMessage());
                }
            }
        }

        LOG.log(System.Logger.Level.INFO, "EventLoop stopped.");
    }

    // -------------------------------------------------------------------------
    // Per-key handlers
    // -------------------------------------------------------------------------

    private void handleRead(SelectionKey key, NioConnection conn) {
        try {
            List<String> frames = conn.read();
            if (frames == null) {
                // EOF: remote peer closed the connection.
                removeAndBroadcastLeave(conn);
                conn.close("peer-eof");
                return;
            }
            for (String payload : frames) {
                dispatcher.dispatch(conn, payload);
            }
        } catch (FrameCodec.FrameException e) {
            conn.sendAndClose(ErrorCode.FRAME_TOO_LARGE.toWire());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Read error conn {0}: {1}", conn.id(), e.getMessage());
            removeAndBroadcastLeave(conn);
            conn.close("read-error");
        }
    }

    private void handleWrite(NioConnection conn) {
        try {
            conn.flush();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Flush error conn {0}: {1}", conn.id(), e.getMessage());
            removeAndBroadcastLeave(conn);
            conn.close("flush-error");
        }
    }

    // -------------------------------------------------------------------------
    // Periodic sweep
    // -------------------------------------------------------------------------

    private void sweep(long now) {
        ConnectionRegistryImpl registry = (ConnectionRegistryImpl) ctx.registry();
        for (Connection c : List.copyOf(registry.allConnections())) {
            if (!(c instanceof NioConnection conn)) continue;
            if (conn.isClosed()) {
                registry.remove(conn);
                continue;
            }

            // Auth timeout: unauthenticated for too long.
            if (!conn.session().authenticated()
                    && (now - conn.connectedAt()) > AUTH_TIMEOUT_MS) {
                conn.sendAndClose(ErrorCode.AUTH_TIMEOUT.toWire());
                registry.remove(conn);
                continue;
            }

            // Idle timeout: no data received for too long.
            if (conn.session().authenticated()
                    && (now - conn.lastActivity()) > IDLE_TIMEOUT_MS) {
                conn.sendAndClose(ErrorCode.IDLE_TIMEOUT.toWire());
                removeAndBroadcastLeave(conn);
                continue;
            }

            // Backpressure: outbox too large — drop the slow consumer.
            if (conn.pendingBytes() > BACKPRESSURE_BYTES) {
                LOG.log(System.Logger.Level.WARNING,
                        "Dropping slow consumer conn {0} ({1} bytes pending)",
                        conn.id(), conn.pendingBytes());
                conn.sendAndClose(ErrorCode.SLOW_CONSUMER.toWire());
                removeAndBroadcastLeave(conn);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void drainTaskQueue() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try { task.run(); }
            catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Task queue error: {0}", e.getMessage());
            }
        }
    }

    private void removeAndBroadcastLeave(NioConnection conn) {
        ConnectionRegistryImpl registry = (ConnectionRegistryImpl) ctx.registry();
        if (conn.session().authenticated()) {
            String user = conn.session().principal().user();
            registry.remove(conn);
            registry.broadcast("LEAVE " + user);
        } else {
            registry.remove(conn);
        }
    }

    private void safeClose(SelectionKey key) {
        try {
            if (key.attachment() instanceof NioConnection conn) {
                removeAndBroadcastLeave(conn);
                conn.close("key-error");
            } else {
                key.cancel();
                key.channel().close();
            }
        } catch (Exception ignored) {}
    }
}
