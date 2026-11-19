package campusnet.server.chat.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Per-connection outbound write queue with OP_WRITE toggling and backpressure (M1).
 *
 * Fast-path: if the queue is empty, attempt a direct write first.
 * Any remaining bytes are queued and OP_WRITE is added to the key
 * so the event loop will drain them on the next writable notification.
 * OP_WRITE is removed as soon as the queue empties to prevent CPU spin.
 */
public class Outbox {

    private final SelectionKey key;
    private final Queue<ByteBuffer> queue = new ArrayDeque<>();
    private long pendingBytes = 0;

    public Outbox(SelectionKey key) {
        this.key = key;
    }

    public long pendingBytes() { return pendingBytes; }
    public boolean isEmpty()   { return queue.isEmpty(); }

    /**
     * Enqueues a frame for delivery.  Tries a fast-path direct write if the
     * queue is empty; queues any remainder and arms OP_WRITE if needed.
     * Must be called only from the selector thread.
     */
    public void enqueue(ByteBuffer frame, SocketChannel channel) throws IOException {
        if (queue.isEmpty()) {
            // Fast path: attempt to write directly.
            channel.write(frame);
        }
        if (frame.hasRemaining()) {
            // Partial write (or queue was not empty) — buffer the rest.
            pendingBytes += frame.remaining();
            queue.add(frame);
            addWriteInterest();
        }
    }

    /**
     * Drains the write queue.  Removes OP_WRITE when the queue empties.
     * Must be called only from the selector thread (on OP_WRITE events).
     *
     * @return {@code true} if the queue is fully drained; {@code false} if the
     *         channel's send buffer is full and more bytes remain.
     */
    public boolean flush(SocketChannel channel) throws IOException {
        while (!queue.isEmpty()) {
            ByteBuffer head = queue.peek();
            long before = head.remaining();
            channel.write(head);
            pendingBytes -= (before - head.remaining());
            if (head.hasRemaining()) {
                // Channel buffer is full — come back on the next OP_WRITE.
                return false;
            }
            queue.poll();
        }
        // Queue fully drained — stop requesting OP_WRITE to avoid CPU spin.
        removeWriteInterest();
        return true;
    }

    // -------------------------------------------------------------------------
    // Interest-set helpers
    // -------------------------------------------------------------------------

    private void addWriteInterest() {
        if (key.isValid()) {
            key.interestOpsOr(SelectionKey.OP_WRITE);
        }
    }

    private void removeWriteInterest() {
        if (key.isValid()) {
            key.interestOpsAnd(~SelectionKey.OP_WRITE);
        }
    }
}
