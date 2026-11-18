package campusnet.server.security;

import campusnet.api.*;

import java.util.Optional;

/**
 * In-memory one-time token service backed by ConcurrentHashMap (M5).
 * I0 stub: issue() returns "STUB"; redeem() always returns empty.
 * Constructor signature is FROZEN — do not change.
 */
public class InMemoryTokenService implements TokenService {

    private final Config config;

    public InMemoryTokenService(Config config) {
        this.config = config;
    }

    @Override
    public String issue(Principal who, Op op, String fileName, long size) {
        // TODO(m5): generate 128-bit SecureRandom hex token; store in ConcurrentHashMap with TTL
        return "STUB";
    }

    @Override
    public Optional<Grant> redeem(String token) {
        // TODO(m5): atomically remove and return the Grant if valid and not expired
        return Optional.empty();
    }
}
