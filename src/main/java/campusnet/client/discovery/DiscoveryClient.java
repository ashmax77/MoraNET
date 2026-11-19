package campusnet.client.discovery;

import campusnet.api.ServerInfo;
import campusnet.api.ServerLocator;
import campusnet.common.Protocol;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * UDP discovery client (M3).
 * I0 stub: locate() returns localhost with default ports.
 * Constructor signature is FROZEN — do not change.
 */
public class DiscoveryClient implements ServerLocator {

    private final int port;

    public DiscoveryClient(int port) {
        this.port = port;
    }

    @Override
    public Optional<ServerInfo> locate(Duration timeout) throws IOException {
        // TODO(m3): send DISCOVER campusnet/1 broadcast; parse SERVER chatPort filePort reply
        return Optional.of(new ServerInfo("localhost",
                Protocol.DEFAULT_CHAT_PORT, Protocol.DEFAULT_FILE_PORT));
    }
}
