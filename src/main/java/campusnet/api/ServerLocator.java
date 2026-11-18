package campusnet.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Locates the server on the LAN via UDP discovery.
 * DiscoveryClient in campusnet.client.discovery implements this.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface ServerLocator {
    /**
     * Try to locate the server within the given timeout.
     * @return the server's address and ports, or empty if not found in time.
     */
    Optional<ServerInfo> locate(Duration timeout) throws IOException;
}
