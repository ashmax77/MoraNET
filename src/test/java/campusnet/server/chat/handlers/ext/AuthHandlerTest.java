package campusnet.server.chat.handlers.ext;

import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.ErrorCode;
import campusnet.testing.FakeConnection;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthHandlerTest {

    private AuthHandler handler;
    private FakeServerContext ctx;
    private FakeConnection conn;

    @BeforeEach
    void setUp() {
        handler = new AuthHandler();
        ctx = new FakeServerContext();
        conn = new FakeConnection();
    }

    @Test
    @DisplayName("Handler contract: verb is AUTH, requiresAuth is false, required is NONE")
    void handlerContract() {
        assertEquals("AUTH", handler.verb());
        assertFalse(handler.requiresAuth());
        assertEquals(campusnet.api.Permission.NONE, handler.required());
    }

    @Test
    @DisplayName("Successful authentication replies OK AUTH <user> <ROLE>")
    void successfulAuthentication() {
        handler.handle(conn, "alice pw1", ctx);

        assertEquals("OK AUTH alice STUDENT", conn.lastSent());
        assertTrue(conn.session().authenticated());
        assertEquals("alice", conn.session().principal().user());
        assertEquals(Role.STUDENT, conn.session().principal().role());
    }

    @Test
    @DisplayName("Already authenticated session replies ERR ALREADY_LOGGED_IN")
    void alreadyAuthenticatedSession() {
        conn.session().authenticate(new Principal("bob", Role.LECTURER));

        handler.handle(conn, "alice pw1", ctx);

        assertEquals(ErrorCode.ALREADY_LOGGED_IN.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("Duplicate login when user is already logged in on another connection")
    void duplicateLoginFails() {
        FakeConnection conn1 = new FakeConnection();
        FakeConnection conn2 = new FakeConnection();

        handler.handle(conn1, "alice pw1", ctx);
        assertEquals("OK AUTH alice STUDENT", conn1.lastSent());

        handler.handle(conn2, "alice pw1", ctx);
        assertEquals(ErrorCode.ALREADY_LOGGED_IN.toWire(), conn2.lastSent());
        assertFalse(conn2.session().authenticated());
    }

    @Test
    @DisplayName("Failed authentication with invalid password replies ERR AUTH_FAILED")
    void invalidPasswordFails() {
        // FakeServerContext provides AcceptAllAuthService which returns STUDENT for any user.
        // Let's test with empty or missing password:
        handler.handle(conn, "alice", ctx); // Missing space and password
        assertEquals(ErrorCode.AUTH_FAILED.toWire(), conn.lastSent());
        assertFalse(conn.session().authenticated());
    }

    @Test
    @DisplayName("Connection is closed after 5 consecutive failed authentication attempts")
    void connectionClosedAfterFiveFailedAttempts() {
        for (int i = 1; i <= 4; i++) {
            handler.handle(conn, "bad-format", ctx);
            assertEquals(ErrorCode.AUTH_FAILED.toWire(), conn.lastSent());
            assertFalse(conn.isClosed(), "Connection must stay open before 5 failed attempts");
        }

        // 5th failed attempt
        handler.handle(conn, "bad-format", ctx);
        assertEquals(ErrorCode.AUTH_FAILED.toWire(), conn.lastSent());
        assertTrue(conn.isClosed(), "Connection must be closed on 5th failed attempt");
    }

    @Test
    @DisplayName("Empty or whitespace args count as failed attempt")
    void emptyArgsCountAsFailedAttempt() {
        handler.handle(conn, "", ctx);
        assertEquals(ErrorCode.AUTH_FAILED.toWire(), conn.lastSent());

        handler.handle(conn, "   ", ctx);
        assertEquals(ErrorCode.AUTH_FAILED.toWire(), conn.lastSent());
    }
}
