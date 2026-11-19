package campusnet.testing;

import campusnet.api.*;
import campusnet.common.PropertiesConfig;

import java.util.*;

/**
 * A fake ServerContext for unit testing handlers.
 * Provides a fake registry, an AuthService that accepts everything,
 * and a TokenService that returns fixed values.
 */
public class FakeServerContext implements ServerContext {

    private final Config config;
    private final FakeRegistry registry;
    private final AcceptAllAuthService auth;
    private final TokenService tokens;
    private boolean allowAll = true;

    public FakeServerContext() {
        this.config = PropertiesConfig.empty();
        this.registry = new FakeRegistry();
        this.auth = new AcceptAllAuthService();
        this.tokens = new StubTokenService();
    }

    public void setAllowAll(boolean allowAll) {
        this.allowAll = allowAll;
    }

    @Override public Config config()              { return config; }
    @Override public ConnectionRegistry registry() { return registry; }
    @Override public AuthService auth()            { return auth; }
    @Override public TokenService tokens()         { return tokens; }

    /** Access the fake registry to inspect state in tests. */
    public FakeRegistry fakeRegistry() { return registry; }

    // ── Inner: FakeRegistry ──────────────────────────────────────────────────

    public static class FakeRegistry implements ConnectionRegistry {
        private final Map<String, Connection> byUser = new LinkedHashMap<>();
        private final List<String> broadcasts = new ArrayList<>();

        @Override
        public boolean bind(Connection c, Principal p) {
            if (byUser.containsKey(p.user())) return false;
            c.session().authenticate(p);
            byUser.put(p.user(), c);
            String joinMsg = "JOIN " + p.user();
            for (Connection other : List.copyOf(byUser.values())) {
                if (other != c) other.send(joinMsg);
            }
            return true;
        }

        @Override
        public Optional<Connection> findByUser(String user) {
            return Optional.ofNullable(byUser.get(user));
        }

        @Override
        public List<Connection> authenticated() {
            return List.copyOf(byUser.values());
        }

        @Override
        public void broadcast(String payload) {
            broadcasts.add(payload);
            for (Connection c : List.copyOf(byUser.values())) {
                try { c.send(payload); } catch (Exception ignored) {}
            }
        }

        public List<String> broadcasts() { return Collections.unmodifiableList(broadcasts); }
    }

    // ── Inner: AcceptAllAuthService ──────────────────────────────────────────

    private class AcceptAllAuthService implements AuthService {
        @Override
        public Optional<Principal> authenticate(String user, String pass) {
            return Optional.of(new Principal(user, Role.STUDENT));
        }

        @Override
        public boolean allowed(Role role, Permission p) {
            if (!allowAll) return false;
            // Mirror the real permission matrix for tests that check ACCESS_DENIED
            if (role == Role.EXTERNAL) {
                return p == Permission.CHAT_BROADCAST || p == Permission.NONE;
            }
            return true;
        }
    }

    // ── Inner: StubTokenService ──────────────────────────────────────────────

    private static class StubTokenService implements TokenService {
        private final Map<String, Grant> tokens = new HashMap<>();

        @Override
        public String issue(Principal who, Op op, String fileName, long size) {
            String token = "TESTTOKEN-" + System.nanoTime();
            tokens.put(token, new Grant(who, op, fileName, size));
            return token;
        }

        @Override
        public Optional<Grant> redeem(String token) {
            return Optional.ofNullable(tokens.remove(token));
        }
    }
}
