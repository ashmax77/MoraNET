package campusnet.server.chat;

import campusnet.common.FrameCodec;
import campusnet.server.chat.net.Outbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxTest {

    // -------------------------------------------------------------------------
    // A minimal SelectionKey stub so we can test without a real selector.
    // -------------------------------------------------------------------------

    private static class StubKey extends SelectionKey {
        private int interestOps = OP_READ;

        @Override public SelectableChannel channel() { return null; }
        @Override public Selector selector()         { return null; }
        @Override public boolean isValid()           { return true; }
        @Override public void cancel()               {}
        @Override public int interestOps()           { return interestOps; }
        @Override public SelectionKey interestOps(int ops) { interestOps = ops; return this; }
        @Override public int readyOps()              { return 0; }

        public boolean hasWriteInterest() {
            return (interestOps & OP_WRITE) != 0;
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Enqueue: when channel drains fully, queue stays empty and OP_WRITE not set")
    void fullWriteNoQueueNoOpWrite() throws Exception {
        StubKey key = new StubKey();
        Outbox outbox = new Outbox(key);

        SocketChannel[] pair = loopbackPair();
        try {
            ByteBuffer frame = FrameCodec.encode("PING");
            outbox.enqueue(frame, pair[0]);

            assertTrue(outbox.isEmpty(), "Queue must be empty when write succeeded fully");
            assertEquals(0, outbox.pendingBytes());
            assertFalse(key.hasWriteInterest(), "OP_WRITE must not be set when queue is empty");
        } finally {
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    @DisplayName("isEmpty returns true initially")
    void initiallyEmpty() {
        StubKey key = new StubKey();
        Outbox outbox = new Outbox(key);
        assertTrue(outbox.isEmpty());
        assertEquals(0, outbox.pendingBytes());
        assertFalse(key.hasWriteInterest());
    }

    @Test
    @DisplayName("flush returns true immediately when queue is empty")
    void flushOnEmptyReturnsTrue() throws Exception {
        StubKey key = new StubKey();
        Outbox outbox = new Outbox(key);

        SocketChannel[] pair = loopbackPair();
        try {
            boolean done = outbox.flush(pair[0]);
            assertTrue(done);
            assertFalse(key.hasWriteInterest());
        } finally {
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    @DisplayName("Partial write: queues remainder, sets OP_WRITE, and flush drains it")
    void partialWriteThenFlushDrain() throws Exception {
        StubKey key = new StubKey();
        Outbox outbox = new Outbox(key);

        SocketChannel[] pair = loopbackPair();
        try {
            SocketChannel writer = pair[0];
            SocketChannel reader = pair[1];

            // Set send buffer small to fill OS buffer quickly
            writer.setOption(StandardSocketOptions.SO_SNDBUF, 4096);
            reader.setOption(StandardSocketOptions.SO_RCVBUF, 4096);

            // Fill channel buffer until channel.write() cannot accept everything
            byte[] bigPayload = new byte[4000];
            java.util.Arrays.fill(bigPayload, (byte) 'A');
            String msg = new String(bigPayload, java.nio.charset.StandardCharsets.UTF_8);

            boolean partialEncountered = false;
            for (int i = 0; i < 500; i++) {
                ByteBuffer frame = FrameCodec.encode(msg);
                outbox.enqueue(frame, writer);
                if (!outbox.isEmpty()) {
                    partialEncountered = true;
                    break;
                }
            }

            assertTrue(partialEncountered, "Should encounter partial write when OS send buffer is full");
            assertTrue(outbox.pendingBytes() > 0);
            assertTrue(key.hasWriteInterest(), "OP_WRITE must be set when outbox has pending bytes");

            // Now drain from the reader side so the OS buffer frees up
            ByteBuffer drainBuf = ByteBuffer.allocate(65536);
            while (reader.read(drainBuf) > 0) {
                drainBuf.clear();
            }

            // Flush the outbox
            boolean drained = outbox.flush(writer);
            // If still not fully drained, read more and flush again
            while (!drained) {
                while (reader.read(drainBuf) > 0) {
                    drainBuf.clear();
                }
                drained = outbox.flush(writer);
            }

            assertTrue(outbox.isEmpty(), "Outbox should be empty after complete flush");
            assertEquals(0, outbox.pendingBytes());
            assertFalse(key.hasWriteInterest(), "OP_WRITE must be cleared once outbox drains");
        } finally {
            pair[0].close();
            pair[1].close();
        }
    }

    // -------------------------------------------------------------------------
    // Helper: create a connected SocketChannel pair over loopback
    // -------------------------------------------------------------------------

    private static SocketChannel[] loopbackPair() throws IOException {
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            SocketChannel server = ssc.accept();
            client.configureBlocking(false);
            server.configureBlocking(false);
            return new SocketChannel[]{ client, server };
        }
    }
}
