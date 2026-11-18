package campusnet.server.discovery;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.common.Protocol;

import java.io.IOException;

/**
 * UDP LAN discovery service on port 8888 (M3).
 * I0 stub: start() does nothing; stop() does nothing.
 * Constructor signature is FROZEN — do not change.
 */
public class DiscoveryService implements Service {

    private final ServerContext ctx;
    private volatile int port;

    public DiscoveryService(ServerContext ctx) {
        this.ctx = ctx;
    }

    @Override public String name() { return "discovery"; }

    @Override
    public void start() throws IOException {
        // TODO(m3): bind DatagramChannel, start receive loop thread
        port = ctx.config().getInt("discovery.port", Protocol.DEFAULT_DISCOVERY_PORT);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                "DiscoveryService stub started (not yet listening)");
    }

    @Override
    public void stop() {
        // TODO(m3): close the DatagramChannel to unblock the receive loop
    }

    @Override
    public int localPort() { return port; }
}
