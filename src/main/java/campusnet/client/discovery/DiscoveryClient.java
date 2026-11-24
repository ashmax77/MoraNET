package campusnet.client.discovery;

import campusnet.api.ServerInfo;
import campusnet.api.ServerLocator;
import campusnet.common.Protocol;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * UDP discovery client (Member 3).
 *
 * <p>Implements {@link ServerLocator} by broadcasting {@code DISCOVER campusnet/1}
 * over UDP and listening for a unicast reply of the form {@code SERVER <chatPort> <filePort>}.
 *
 * <p>Broadcast targets:
 * <ol>
 *   <li>Global broadcast — {@code 255.255.255.255}</li>
 *   <li>Directed broadcast addresses of every active, non-loopback network interface</li>
 *   <li>{@code 127.0.0.1} — single-machine demonstration fallback</li>
 * </ol>
 *
 * <p>The server host IP is taken from the reply datagram's source address, never from
 * a constant or the response text.
 *
 * <p>Constructor signature is FROZEN — do not change.
 */
public class DiscoveryClient implements ServerLocator {

    private static final System.Logger LOG =
            System.getLogger(DiscoveryClient.class.getName());

    /** Exact discovery request text per Section 5.3 of ARCHITECTURE.md. */
    private static final String DISCOVER_REQUEST = "DISCOVER " + Protocol.VERSION;

    /** Expected server response prefix. */
    private static final String SERVER_PREFIX = "SERVER ";

    /** Maximum UDP datagram size as per Section 5.3. */
    private static final int MAX_DATAGRAM_BYTES = 512;

    /** Buffer is one byte larger to detect oversized datagrams. */
    private static final int RECEIVE_BUFFER_SIZE = MAX_DATAGRAM_BYTES + 1;

    /** Maximum number of discovery attempts. */
    private static final int MAX_ATTEMPTS = 3;

    /** Duration of each individual attempt in nanoseconds (1 second). */
    private static final long ATTEMPT_TIMEOUT_NS = 1_000_000_000L;

    // ── State ─────────────────────────────────────────────────────────────────

    private final int port;

    // ── Constructor (FROZEN) ──────────────────────────────────────────────────

    /**
     * Creates a {@code DiscoveryClient} targeting the given UDP discovery port.
     *
     * @param port the UDP port on which the discovery service is listening (default 8888)
     */
    public DiscoveryClient(int port) {
        this.port = port;
    }

    // ── ServerLocator interface ───────────────────────────────────────────────

    /**
     * Attempts to locate the server on the LAN using UDP broadcast discovery.
     *
     * <p>Makes up to {@value MAX_ATTEMPTS} attempts, each with a 1-second timeout
     * (or less if the caller-provided {@code timeout} is shorter). Uses
     * {@link System#nanoTime()} for all deadline tracking.
     *
     * <p>Guarantees that the no-server case completes within 3.5 seconds when
     * called with a timeout of at least 3 seconds.
     *
     * @param timeout the overall timeout for all discovery attempts combined
     * @return a populated {@link ServerInfo} if a server responded, or empty
     * @throws IOException if a network error prevents opening the channel
     */
    @Override
    public Optional<ServerInfo> locate(Duration timeout) throws IOException {
        long deadlineNs = System.nanoTime() + timeout.toNanos();

        List<InetSocketAddress> targets = collectBroadcastTargets();

        try (DatagramChannel ch = DatagramChannel.open();
             Selector sel = Selector.open()) {

            ch.setOption(StandardSocketOptions.SO_BROADCAST, true);
            ch.configureBlocking(false);
            ch.bind(null); // bind to an ephemeral local port
            ch.register(sel, SelectionKey.OP_READ);

            byte[] requestBytes = DISCOVER_REQUEST.getBytes(StandardCharsets.UTF_8);
            ByteBuffer recvBuf = ByteBuffer.allocate(RECEIVE_BUFFER_SIZE);

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "DiscoveryClient: overall timeout expired before attempt {0}", attempt);
                    break;
                }

                // Per-attempt timeout is 1 second, capped by remaining overall time.
                long attemptTimeoutNs = Math.min(ATTEMPT_TIMEOUT_NS, remainingNs);

                LOG.log(System.Logger.Level.DEBUG,
                        "DiscoveryClient: attempt {0}/{1}, timeout {2} ms",
                        attempt, MAX_ATTEMPTS, attemptTimeoutNs / 1_000_000);

                // Send discovery request to all broadcast targets.
                sendToAllTargets(ch, requestBytes, targets);

                // Wait for a valid reply within this attempt's timeout.
                Optional<ServerInfo> result = waitForReply(ch, sel, recvBuf, attemptTimeoutNs);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        LOG.log(System.Logger.Level.INFO,
                "DiscoveryClient: no server found after {0} attempts on port {1}",
                MAX_ATTEMPTS, port);
        return Optional.empty();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sends the discovery request datagram to every broadcast target address.
     * Network interface errors are logged and skipped without aborting discovery.
     *
     * @param ch           the channel to send from
     * @param requestBytes the encoded discovery request
     * @param targets      list of destination addresses
     */
    private void sendToAllTargets(DatagramChannel ch,
                                  byte[] requestBytes,
                                  List<InetSocketAddress> targets) {
        for (InetSocketAddress target : targets) {
            try {
                ByteBuffer buf = ByteBuffer.wrap(requestBytes);
                ch.send(buf, target);
                LOG.log(System.Logger.Level.DEBUG,
                        "DiscoveryClient: sent DISCOVER to {0}", target);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        "DiscoveryClient: could not send to {0}: {1}", target, e.getMessage());
            }
        }
    }

    /**
     * Waits for a valid {@code SERVER <chatPort> <filePort>} reply within
     * {@code attemptTimeoutNs} nanoseconds. Uses {@link Selector#select(long)}
     * in a deadline loop so the actual elapsed time is bounded precisely.
     *
     * @param ch               the channel to receive on
     * @param sel              the selector registered for OP_READ on ch
     * @param recvBuf          reused receive buffer (will be cleared before use)
     * @param attemptTimeoutNs the timeout for this attempt in nanoseconds
     * @return a populated {@link ServerInfo} on success, or empty on timeout
     */
    private Optional<ServerInfo> waitForReply(DatagramChannel ch,
                                              Selector sel,
                                              ByteBuffer recvBuf,
                                              long attemptTimeoutNs) throws IOException {
        long attemptDeadlineNs = System.nanoTime() + attemptTimeoutNs;

        while (true) {
            long remainingNs = attemptDeadlineNs - System.nanoTime();
            if (remainingNs <= 0) {
                return Optional.empty();
            }

            long selectMs = Math.max(1L, remainingNs / 1_000_000);
            int ready = sel.select(selectMs);

            if (ready == 0) {
                // Timeout or spurious wakeup — recheck deadline.
                if (System.nanoTime() >= attemptDeadlineNs) {
                    return Optional.empty();
                }
                continue;
            }

            // Drain all ready keys (there is only one channel registered).
            sel.selectedKeys().clear();

            recvBuf.clear();
            SocketAddress srcAddr = ch.receive(recvBuf);
            if (srcAddr == null) {
                continue;
            }

            recvBuf.flip();
            int received = recvBuf.limit();

            // Reject datagrams exceeding the 512-byte maximum.
            if (received > MAX_DATAGRAM_BYTES) {
                LOG.log(System.Logger.Level.DEBUG,
                        "DiscoveryClient: oversized datagram ({0} bytes) from {1}, ignored",
                        received, srcAddr);
                continue;
            }

            byte[] bytes = new byte[received];
            recvBuf.get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8).trim();

            Optional<ServerInfo> parsed = parseReply(text, srcAddr);
            if (parsed.isPresent()) {
                return parsed;
            }
            // Invalid reply — keep waiting until this attempt's deadline.
        }
    }

    /**
     * Parses a {@code SERVER <chatPort> <filePort>} reply.
     * Obtains the server host from the datagram's source address.
     *
     * @param text    the decoded response text
     * @param srcAddr the source socket address of the reply datagram
     * @return populated {@link ServerInfo} on a valid reply, or empty
     */
    private Optional<ServerInfo> parseReply(String text, SocketAddress srcAddr) {
        if (!text.startsWith(SERVER_PREFIX)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: unexpected reply text: '{0}', ignored", text);
            return Optional.empty();
        }

        String[] parts = text.substring(SERVER_PREFIX.length()).trim().split("\\s+");
        if (parts.length != 2) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: malformed reply (wrong token count): '{0}'", text);
            return Optional.empty();
        }

        int chatPort;
        int filePort;
        try {
            chatPort = Integer.parseInt(parts[0]);
            filePort = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: non-numeric port in reply: '{0}'", text);
            return Optional.empty();
        }

        if (!isValidPort(chatPort) || !isValidPort(filePort)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: out-of-range port in reply: '{0}'", text);
            return Optional.empty();
        }

        if (!(srcAddr instanceof InetSocketAddress inetSrc)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: non-InetSocketAddress source: {0}", srcAddr);
            return Optional.empty();
        }

        // Server host IP comes from the reply packet's source address — never hard-coded.
        String host = inetSrc.getAddress().getHostAddress();

        LOG.log(System.Logger.Level.INFO,
                "DiscoveryClient: discovered server at {0} chatPort={1} filePort={2}",
                host, chatPort, filePort);

        return Optional.of(new ServerInfo(host, chatPort, filePort));
    }

    /**
     * Builds the list of broadcast addresses to target:
     * <ol>
     *   <li>Global broadcast {@code 255.255.255.255}</li>
     *   <li>Directed broadcast of every active, non-loopback IPv4 interface</li>
     *   <li>Loopback fallback {@code 127.0.0.1}</li>
     * </ol>
     * Network interface enumeration errors are caught and logged without crashing.
     *
     * @return an ordered list of target socket addresses
     */
    private List<InetSocketAddress> collectBroadcastTargets() {
        List<InetSocketAddress> targets = new ArrayList<>();

        // 1. Global broadcast.
        try {
            targets.add(new InetSocketAddress(
                    InetAddress.getByName("255.255.255.255"), port));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: could not resolve 255.255.255.255: {0}", e.getMessage());
        }

        // 2. Directed broadcast addresses from active non-loopback interfaces.
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics != null) {
                while (nics.hasMoreElements()) {
                    NetworkInterface nic = nics.nextElement();
                    try {
                        if (!nic.isUp() || nic.isLoopback()) {
                            continue;
                        }
                        for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                            InetAddress broadcast = ia.getBroadcast();
                            if (broadcast != null && broadcast instanceof Inet4Address) {
                                targets.add(new InetSocketAddress(broadcast, port));
                                LOG.log(System.Logger.Level.DEBUG,
                                        "DiscoveryClient: added directed broadcast {0} from {1}",
                                        broadcast, nic.getName());
                            }
                        }
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.DEBUG,
                                "DiscoveryClient: error inspecting NIC {0}: {1}",
                                nic.getName(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "DiscoveryClient: could not enumerate network interfaces: {0}",
                    e.getMessage());
        }

        // 3. Loopback fallback for single-machine demos.
        try {
            targets.add(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "DiscoveryClient: could not resolve 127.0.0.1: {0}", e.getMessage());
        }

        return targets;
    }

    /**
     * Returns {@code true} if {@code p} is a valid UDP/TCP port number (1 to 65535).
     *
     * @param p the port number to check
     * @return {@code true} if the port is valid
     */
    private static boolean isValidPort(int p) {
        return p >= 1 && p <= 65535;
    }
}
