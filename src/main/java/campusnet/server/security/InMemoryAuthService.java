package campusnet.server.security;

import campusnet.api.*;

import java.util.Optional;

/**
 * In-memory authentication and permission service (M5).
 * I0 stub: accepts any username as STUDENT; allowed() always returns true.
 * Constructor signature is FROZEN — do not change.
 */
public class InMemoryAuthService implements AuthService {

    private final Config config;

    public InMemoryAuthService(Config config) {
        this.config = config;
    }

    @Override
    public Optional<Principal> authenticate(String user, String pass) {
        // TODO(m5): load users from config (user.name=password,ROLE) and verify credentials
        return Optional.of(new Principal(user, Role.STUDENT));
    }

    @Override
    public boolean allowed(Role role, Permission p) {
        // TODO(m5): implement the permission matrix from ARCHITECTURE.md section 6
        return true;
    }
}
