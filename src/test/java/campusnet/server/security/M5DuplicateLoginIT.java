package campusnet.server.security;

import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;
import campusnet.common.PropertiesConfig;
import campusnet.server.chat.core.ConnectionRegistryImpl;
import campusnet.server.chat.core.DefaultServerContext;
import campusnet.server.chat.core.Dispatcher;
import campusnet.server.chat.handlers.ext.AuthHandler;
import campusnet.testing.FakeConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration scenario IT2: Duplicate login rejection and registry unbinding.
 * Verifies that a duplicate login attempt for an active user is rejected with ERR ALREADY_LOGGED_IN,
 * and after disconnect/unbinding, the username becomes available again.
 */
@Tag("integration")
class M5DuplicateLoginIT {

    private Dispatcher dispatcher;
    private ConnectionRegistryImpl registry;
    private InMemoryAuthService auth;
    private InMemoryTokenService tokens;

    @BeforeEach
    void setUp() {
        PropertiesConfig config = PropertiesConfig.empty();
        registry = new ConnectionRegistryImpl();
        auth = new InMemoryAuthService(config);
        tokens = new InMemoryTokenService(config);

        ServerContext ctx = new DefaultServerContext(config, registry, auth, tokens);
        dispatcher = new Dispatcher();
        dispatcher.setContext(ctx);

        dispatcher.register(new AuthHandler());
    }

    @Test
    @DisplayName("IT2: Duplicate login is rejected; user can log in again after unbinding")
    void duplicateLoginRejectedAndUnbound() {
        FakeConnection conn1 = new FakeConnection();
        registry.add(conn1);

        // First login succeeds
        dispatcher.dispatch(conn1, "AUTH alice pw1");
        assertEquals("OK AUTH alice STUDENT", conn1.lastSent());
        assertTrue(conn1.session().authenticated());
        assertEquals("alice", conn1.session().principal().user());
        assertTrue(registry.findByUser("alice").isPresent());

        // Second connection attempts to log in as alice: rejected
        FakeConnection conn2 = new FakeConnection();
        registry.add(conn2);
        dispatcher.dispatch(conn2, "AUTH alice pw1");
        assertEquals(ErrorCode.ALREADY_LOGGED_IN.toWire(), conn2.lastSent());
        assertFalse(conn2.session().authenticated());

        // First connection disconnects / removed from registry
        registry.remove(conn1);
        conn1.close("client disconnected");
        assertTrue(registry.findByUser("alice").isEmpty());

        // Now conn2 can log in as alice
        conn2.clearSent();
        dispatcher.dispatch(conn2, "AUTH alice pw1");
        assertEquals("OK AUTH alice STUDENT", conn2.lastSent());
        assertTrue(conn2.session().authenticated());
        assertTrue(registry.findByUser("alice").isPresent());
    }
}
