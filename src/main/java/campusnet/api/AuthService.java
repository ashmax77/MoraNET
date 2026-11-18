package campusnet.api;

import java.util.Optional;

/**
 * Authenticates users and enforces the permission matrix.
 * InMemoryAuthService in campusnet.server.security is the production implementation.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface AuthService {
    /**
     * Verify credentials.
     * @return the Principal if valid, empty if credentials are wrong.
     */
    Optional<Principal> authenticate(String user, String pass);

    /**
     * Check whether a role has a given permission.
     * @return true if the role may perform the operation.
     */
    boolean allowed(Role role, Permission p);
}
