package campusnet.server.discovery;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.common.Protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP LAN discovery service (Member 3).
 *
 * <p>Binds a {@link DatagramChannel} to {@code discovery.port} (default 8888) and
 * answers the exact discovery request {@code DISCOVER campusnet/1} with
 * {@code SERVER <chatPort> <filePort>} unicast back to the sender.
 *
 * <p>All other datagrams are silently ignored. A per-source rate limiter caps
 * replies at 10 per second per remote address. Logging uses {@link System.Logger}.
 *
 * <p>{@link #start()} blocks until the receive loop thread has entered its first
 * {@code receive()} call, so callers can send discovery requests immediately after
 * {@code start()} returns.
 *
 * <p>Constructor signature is FROZEN — do not change.
 */
public class DiscoveryService implements Service {

    private static final System.Logger LOG =
            System.getLogger(DiscoveryService.class.getName());

    /** Maximum UDP discovery datagram size as per Section 5.3. */
    private static final int MAX_DATAGRAM_BYTES = 512;

    /** Buffer is one byte larger so we can detect oversized datagrams. */
    private static final int RECEIVE_BUFFER_SIZE = MAX_DATAGRAM_BYTES + 1;

    /** Exact discovery request text that must match. */
    private static final String DISCOVER_REQUEST = "DISCOVER " + Protocol.VERSION;

    /** Maximum replies allowed per source address per second. */
    private static final int MAX_REPLIES_PER_SECOND = 10;

    // ── State ─────────────────────────────────────────────────────────────────

    private final ServerContext ctx;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Guarded by rateLimiterLock. */
    private final Map<InetAddress, RateBucket> rateLimiter = new LinkedHashMap<>(64, 0.75f, true);
    private final Object rateLimiterLock = new Object();

    private volatile DatagramChannel channel;
    private volatile Thread discoveryThread;
    private volatile int boundPort;

    // ── Constructor (FROZEN) ──────────────────────────────────────────────────

    /**
     * Creates a new DiscoveryService backed by the given server context.
     *
     * @param ctx the shared server context (config, registry, auth, tokens)
     */
    public DiscoveryService(ServerContext ctx) {
        this.ctx = ctx;
    }

    // ── Service interface ─────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "discovery";
    }

    /**
     * Binds the UDP {@link DatagramChannel} and starts the discovery receive thread.
     * Blocks until the receive loop thread has entered its first {@code receive()} call,
     * so callers can safely send discovery datagrams immediately after this returns.
     *
     * @throws IOException if the channel cannot be opened, bound, or the thread fails to start
     */
    @Override
    public void start() throws IOException {
        int configuredPort = ctx.config().getInt("discovery.port", Protocol.DEFAULT_DISCOVERY_PORT);

        DatagramChannel ch = DatagramChannel.open();
        ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ch.bind(new InetSocketAddress(configuredPort));

        this.channel = ch;
        this.boundPort = ((InetSocketAddress) ch.getLocalAddress()).getPort();

        // Latch that the receive loop signals once it enters the first receive() call.
        CountDownLatch ready = new CountDownLatch(1);

        Thread t = new Thread(() -> receiveLoop(ready), "discovery-service");
        t.setDaemon(true);
        this.discoveryThread = t;
        t.start();

        // Wait up to 2 seconds for the loop to be ready.
        try {
            if (!ready.await(2, TimeUnit.SECONDS)) {
                throw new IOException("DiscoveryService: receive loop did not start within 2 seconds");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("DiscoveryService: interrupted while waiting for receive loop", ie);
        }

        LOG.log(System.Logger.Level.INFO,
                "DiscoveryService started on UDP port {0}", boundPort);
    }

    /**
     * Stops the service idempotently. Closes the channel (which unblocks the
     * receive loop) and waits up to 1 second for the thread to exit.
     */
    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return; // already stopped
        }
        DatagramChannel ch = this.channel;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "DiscoveryService: error closing channel during stop: {0}", e.getMessage());
            }
        }
        Thread t = this.discoveryThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.log(System.Logger.Level.INFO, "DiscoveryService stopped.");
    }

    /**
     * Returns the actual local port after {@link #start()} has been called.
     * Useful when the service was bound to port 0 for integration tests.
     *
     * @return the bound UDP port number, or 0 before {@link #start()} is called
     */
    @Override
    public int localPort() {
        return boundPort;
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    /**
     * Blocking receive loop running on the dedicated discovery thread.
     * Signals {@code ready} once it has entered the first {@code receive()} call.
     * Terminates when the channel is closed ({@code stop()} was called).
     *
     * @param ready latch to count down once the loop is ready to receive
     */
    private void receiveLoop(CountDownLatch ready) {
        // Allocate one extra byte to detect oversized datagrams.
        ByteBuffer buf = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

        boolean signalled = false;
        while (!stopped.get()) {
            buf.clear();

            // Signal that we are about to block on receive — start() can return now.
            if (!signalled) {
                ready.countDown();
                signalled = true;
            }

            SocketAddress sender;
            try {
                sender = channel.receive(buf);
            } catch (ClosedChannelException e) {
                // Normal shutdown path — stop() closed the channel.
                // AsynchronousCloseException is a subclass of ClosedChannelException.
                break;
            } catch (IOException e) {
                if (!stopped.get()) {
                    LOG.log(System.Logger.Level.WARNING,
                            "DiscoveryService: I/O error in receive loop: {0}", e.getMessage());
                }
                break;
            }

            if (sender == null) {
                continue;
            }

            handleDatagram(buf, sender);
        }

        LOG.log(System.Logger.Level.INFO, "DiscoveryService receive loop exited.");
    }

    /**
     * Processes one received datagram. Silently ignores anything that does not
     * match the exact discovery request text or exceeds the size limit.
     *
     * @param buf    the receive buffer after a successful channel.receive()
     * @param sender the source socket address of the datagram
     */
    private void handleDatagram(ByteBuffer buf, SocketAddress sender) {
        buf.flip();
        int received = buf.limit();

        // Reject datagrams that exceed the 512-byte maximum.
        if (received > MAX_DATAGRAM_BYTES) {
            return;
        }

        // Decode the payload.
        byte[] bytes = new byte[received];
        buf.get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8).trim();

        // Validate the exact discovery request.
        if (!DISCOVER_REQUEST.equals(text)) {
            return;
        }

        // Require an InetSocketAddress to extract the source IP for rate limiting.
        if (!(sender instanceof InetSocketAddress inetSender)) {
            return;
        }
        InetAddress sourceIp = inetSender.getAddress();

        // Apply per-source rate limiting.
        if (!allowReply(sourceIp)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryService: rate limit exceeded for {0}, dropping reply", sourceIp);
            return;
        }

        // Read chat and file ports from config.
        int chatPort = ctx.config().getInt("chat.port", Protocol.DEFAULT_CHAT_PORT);
        int filePort = ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT);

        String response = "SERVER " + chatPort + " " + filePort;
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

        try {
            channel.send(ByteBuffer.wrap(responseBytes), sender);
            LOG.log(System.Logger.Level.INFO,
                    "DiscoveryService: answered {0} -> {1}", sender, response);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "DiscoveryService: failed to send reply to {0}: {1}", sender, e.getMessage());
        }
    }

    // ── Rate limiter ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the source address is within the rate limit
     * (at most {@value MAX_REPLIES_PER_SECOND} replies per second).
     *
     * <p>Uses {@link System#nanoTime()} for timing. Expired buckets are removed to
     * bound memory. The tracked address table is capped at 1024 entries so a
     * flood of unique source IPs cannot exhaust memory.
     *
     * @param source the source IP address of the discovery request
     * @return {@code true} if a reply is permitted
     */
    private boolean allowReply(InetAddress source) {
        long nowNs = System.nanoTime();
        long windowNs = 1_000_000_000L; // 1 second in nanoseconds
        int maxTracked = 1024;

        synchronized (rateLimiterLock) {
            // Evict expired entries to bound memory.
            Iterator<Map.Entry<InetAddress, RateBucket>> it = rateLimiter.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<InetAddress, RateBucket> entry = it.next();
                if (nowNs - entry.getValue().windowStartNs > windowNs) {
                    it.remove();
                }
            }

            // Cap the table size to prevent memory exhaustion from unique IPs.
            if (!rateLimiter.containsKey(source) && rateLimiter.size() >= maxTracked) {
                // LinkedHashMap in access-order removes the LRU entry.
                rateLimiter.remove(rateLimiter.keySet().iterator().next());
            }

            RateBucket bucket = rateLimiter.get(source);
            if (bucket == null || nowNs - bucket.windowStartNs > windowNs) {
                rateLimiter.put(source, new RateBucket(nowNs, 1));
                return true;
            }
            if (bucket.count < MAX_REPLIES_PER_SECOND) {
                bucket.count++;
                return true;
            }
            return false;
        }
    }

    // ── Inner class: RateBucket ───────────────────────────────────────────────

    /**
     * Mutable token-bucket state per source IP, tracking the reply count in a 1-second window.
     */
    private static final class RateBucket {
        long windowStartNs;
        int count;

        RateBucket(long windowStartNs, int count) {
            this.windowStartNs = windowStartNs;
            this.count = count;
        }
    }
}
