package campusnet.server.security;

import campusnet.api.Config;
import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.api.Principal;
import campusnet.api.TokenService;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory one-time token service (M5).
 * Generates 128-bit SecureRandom hex tokens, bounds lifetimes with token.ttlSec,
 * enforces per-user outstanding token caps, and purges expired entries atomically.
 *
 * Constructor signature is FROZEN — do not change.
 */
public class InMemoryTokenService implements TokenService {

    private static final System.Logger LOG = System.getLogger(InMemoryTokenService.class.getName());

    private static final int TOKEN_BYTES = 16; // 128-bit
    private static final int DEFAULT_TTL_SEC = 60;
    private static final int DEFAULT_MAX_TOKENS_PER_USER = 5;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, TokenEntry> tokenMap = new ConcurrentHashMap<>();
    private final Config config;

    /**
     * Constructs an InMemoryTokenService with the specified configuration.
     *
     * @param config the server configuration
     */
    public InMemoryTokenService(Config config) {
        this.config = config;
    }

    /**
     * Issues a new 128-bit hex one-time token bound to the grant parameters.
     * Purges expired entries and enforces the per-user token cap.
     *
     * @param who      the principal requesting the token
     * @param op       the requested operation (PUT or GET)
     * @param fileName the target file name
     * @param size     the expected file size in bytes (0 for GET)
     * @return 128-bit random hex string token
     * @throws IllegalArgumentException if any argument is null or invalid
     * @throws IllegalStateException    if the user has reached the maximum allowed outstanding tokens
     */
    @Override
    public String issue(Principal who, Op op, String fileName, long size) {
        if (who == null) throw new IllegalArgumentException("Principal must not be null");
        if (op == null) throw new IllegalArgumentException("Op must not be null");
        if (fileName == null || fileName.isEmpty()) throw new IllegalArgumentException("FileName must not be null or empty");
        if (size < 0) throw new IllegalArgumentException("Size cannot be negative");

        long now = System.nanoTime();
        purgeExpired(now);

        int maxPerUser = config != null ? config.getInt("token.maxPerUser", DEFAULT_MAX_TOKENS_PER_USER) : DEFAULT_MAX_TOKENS_PER_USER;
        long userTokenCount = tokenMap.values().stream()
                .filter(entry -> entry.grant().who().user().equals(who.user()) && entry.expiresAtNanos() > now)
                .count();

        if (userTokenCount >= maxPerUser) {
            LOG.log(System.Logger.Level.WARNING, "Token limit of {0} exceeded for user {1}", maxPerUser, who.user());
            throw new IllegalStateException("Maximum outstanding tokens (" + maxPerUser + ") reached for user: " + who.user());
        }

        int ttlSec = config != null ? config.getInt("token.ttlSec", DEFAULT_TTL_SEC) : DEFAULT_TTL_SEC;
        long ttlNanos = ttlSec * 1_000_000_000L;
        long expiresAtNanos = now + ttlNanos;

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        Grant grant = new Grant(who, op, fileName, size);
        tokenMap.put(token, new TokenEntry(grant, expiresAtNanos));

        LOG.log(System.Logger.Level.INFO, "Issued token {0}... for user {1}, op {2}, file {3}",
                tokenPrefix(token), who.user(), op, fileName);

        return token;
    }

    /**
     * Redeems a token atomically. Removes the token so it can only be redeemed once.
     *
     * @param token the token string
     * @return the Grant if the token was valid and unexpired; empty otherwise
     */
    @Override
    public Optional<Grant> redeem(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }

        TokenEntry entry = tokenMap.remove(token);
        if (entry == null) {
            LOG.log(System.Logger.Level.WARNING, "Redeem failed: token {0}... not found", tokenPrefix(token));
            return Optional.empty();
        }

        if (System.nanoTime() > entry.expiresAtNanos()) {
            LOG.log(System.Logger.Level.WARNING, "Redeem failed: token {0}... expired", tokenPrefix(token));
            return Optional.empty();
        }

        LOG.log(System.Logger.Level.INFO, "Redeemed token {0}... for user {1}, op {2}",
                tokenPrefix(token), entry.grant().who().user(), entry.grant().op());
        return Optional.of(entry.grant());
    }

    /**
     * Purges expired entries from the token map.
     */
    private void purgeExpired(long now) {
        tokenMap.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    /**
     * Safely formats the first 4 characters of a token for logging (never log full tokens).
     */
    private String tokenPrefix(String token) {
        if (token == null) return "null";
        return token.substring(0, Math.min(4, token.length()));
    }

    private record TokenEntry(Grant grant, long expiresAtNanos) {}
}
