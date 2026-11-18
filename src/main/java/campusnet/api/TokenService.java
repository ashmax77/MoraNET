package campusnet.api;

import java.util.Optional;

/**
 * Issues and redeems one-time file-transfer tokens.
 * MUST be thread-safe — the chat thread issues tokens and file server threads redeem them.
 * InMemoryTokenService in campusnet.server.security is the production implementation.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface TokenService {
    /**
     * Issue a new one-time token.
     * @return a random token string (128-bit hex in production).
     */
    String issue(Principal who, Op op, String fileName, long size);

    /**
     * Redeem a token. Removes it atomically (guarantees one-time use).
     * @return the Grant if the token is valid and not expired; empty otherwise.
     */
    Optional<Grant> redeem(String token);
}
