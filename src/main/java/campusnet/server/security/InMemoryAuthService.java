package campusnet.server.security;

import campusnet.api.AuthService;
import campusnet.api.Config;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.PropertiesConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory authentication and role-based access control service (M5).
 * Loads user credentials from configuration, verifies passwords in constant time,
 * and enforces the role permission matrix.
 *
 * Constructor signature is FROZEN — do not change.
 */
public class InMemoryAuthService implements AuthService {

    private static final System.Logger LOG = System.getLogger(InMemoryAuthService.class.getName());

    // Default users as specified in ARCHITECTURE.md section 3.3
    private static final Map<String, UserEntry> DEFAULT_USERS = Map.of(
            "alice", new UserEntry("pw1", Role.STUDENT),
            "bob", new UserEntry("pw2", Role.LECTURER),
            "partner", new UserEntry("pw3", Role.EXTERNAL)
    );

    private final Config config;

    /**
     * Constructs an InMemoryAuthService using the provided Config.
     *
     * @param config the server configuration
     */
    public InMemoryAuthService(Config config) {
        this.config = config != null ? config : PropertiesConfig.empty();
    }

    /**
     * Authenticates a user with constant-time password verification.
     *
     * @param user the username
     * @param pass the plaintext password
     * @return Optional containing the Principal on success, or empty on failure
     */
    @Override
    public Optional<Principal> authenticate(String user, String pass) {
        if (user == null || user.isEmpty() || pass == null) {
            return Optional.empty();
        }

        UserEntry entry = resolveUser(user);
        byte[] providedPass = pass.getBytes(StandardCharsets.UTF_8);

        if (entry == null) {
            // Constant-time dummy comparison to prevent username enumeration via timing attacks
            MessageDigest.isEqual(providedPass, providedPass);
            return Optional.empty();
        }

        byte[] expectedPass = entry.password().getBytes(StandardCharsets.UTF_8);
        boolean matches = MessageDigest.isEqual(providedPass, expectedPass);

        if (matches) {
            LOG.log(System.Logger.Level.INFO, "User {0} authenticated successfully with role {1}", user, entry.role());
            return Optional.of(new Principal(user, entry.role()));
        } else {
            LOG.log(System.Logger.Level.WARNING, "Authentication failed for user {0}", user);
            return Optional.empty();
        }
    }

    /**
     * Evaluates role-based permission according to the permission matrix in section 6.
     *
     * @param role the role of the principal
     * @param p    the permission requested
     * @return true if the role is granted the permission, false otherwise
     */
    @Override
    public boolean allowed(Role role, Permission p) {
        if (role == null || p == null) {
            return false;
        }

        if (p == Permission.NONE) {
            return true;
        }

        boolean granted = switch (role) {
            case STUDENT, LECTURER -> true;
            case EXTERNAL -> p == Permission.CHAT_BROADCAST;
        };

        if (!granted) {
            LOG.log(System.Logger.Level.WARNING, "ACCESS_DENIED: role {0} denied permission {1}", role, p);
        }

        return granted;
    }

    /**
     * Resolves user credentials from config or falls back to demo defaults.
     */
    private UserEntry resolveUser(String user) {
        String configVal = config.get("user." + user, null);
        if (configVal != null && !configVal.isBlank()) {
            int comma = configVal.indexOf(',');
            if (comma != -1) {
                String pass = configVal.substring(0, comma).trim();
                String roleStr = configVal.substring(comma + 1).trim();
                try {
                    Role role = Role.valueOf(roleStr);
                    return new UserEntry(pass, role);
                } catch (IllegalArgumentException e) {
                    LOG.log(System.Logger.Level.WARNING, "Invalid role configured for user {0}: {1}", user, roleStr);
                }
            }
        }
        return DEFAULT_USERS.get(user);
    }

    private record UserEntry(String password, Role role) {}
}
