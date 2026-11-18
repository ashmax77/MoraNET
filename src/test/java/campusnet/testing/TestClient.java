package campusnet.testing;

import campusnet.common.FrameCodec;
import campusnet.common.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple blocking TCP client for integration tests.
 * Sends and receives length-prefixed frames.
 *
 * Usage:
 *   TestClient client = new TestClient("127.0.0.1", server.port());
 *   String welcome = client.receive(2000);  // wait up to 2 seconds
 *   client.send("PING");
 *   String pong = client.receive(2000);
 *   client.close();
 */
public class TestClient implements AutoCloseable {

    private final SocketChannel channel;
    private final ByteBuffer readBuf = ByteBuffer.allocate(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD + 100);

    public TestClient(String host, int port) throws IOException {
        this.channel = SocketChannel.open();
        this.channel.configureBlocking(false);
        this.channel.connect(new InetSocketAddress(host, port));

        // Finish non-blocking connect
        long deadline = System.currentTimeMillis() + 3000;
        while (!channel.finishConnect()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("TestClient: connect timed out");
            }
            Thread.yield();
        }
    }

    /** Send a message as a length-prefixed frame. */
    public void send(String payload) throws Exception {
        ByteBuffer frame = FrameCodec.encode(payload);
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /**
     * Wait for one complete message to arrive, up to timeoutMs.
     * @return the decoded message
     * @throws IOException if the channel is closed or the timeout expires
     */
    public String receive(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n = channel.read(readBuf);
            if (n == -1) throw new IOException("TestClient: server closed the connection");
            if (n > 0) {
                readBuf.flip();
                List<String> msgs = FrameCodec.decode(readBuf);
                readBuf.compact();
                if (!msgs.isEmpty()) return msgs.get(0);
            }
            Thread.sleep(5);
        }
        throw new IOException("TestClient: receive timed out after " + timeoutMs + " ms");
    }

    /**
     * Wait for up to `count` messages, up to timeoutMs total.
     */
    public List<String> receiveN(int count, long timeoutMs) throws Exception {
        List<String> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (collected.size() < count && System.currentTimeMillis() < deadline) {
            int n = channel.read(readBuf);
            if (n == -1) break;
            if (n > 0) {
                readBuf.flip();
                collected.addAll(FrameCodec.decode(readBuf));
                readBuf.compact();
            } else {
                Thread.sleep(5);
            }
        }
        return collected;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
