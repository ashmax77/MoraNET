package campusnet.server.chat.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Per-connection outbound write queue with OP_WRITE toggling and backpressure (M1).
 * I0 stub: empty placeholder. Full implementation in milestone I1.
 */
public class Outbox {

    private final SelectionKey key;
    private final Queue<ByteBuffer> queue = new ArrayDeque<>();
    private long pendingBytes = 0;

    public Outbox(SelectionKey key) {
        this.key = key;
    }

    public long pendingBytes() { return pendingBytes; }
    public boolean isEmpty() { return queue.isEmpty(); }

    public void enqueue(ByteBuffer frame, SocketChannel channel) throws IOException {
        // TODO(m1, #1): fast-path write; queue remainder; set OP_WRITE
    }

    public boolean flush(SocketChannel channel) throws IOException {
        // TODO(m1, #1): write queued buffers; remove OP_WRITE when queue is empty
        return true;
    }
}
