package campusnet.server.chat;

import campusnet.api.*;
import campusnet.common.ErrorCode;
import campusnet.server.chat.core.Dispatcher;
import campusnet.testing.FakeConnection;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {

    private Dispatcher dispatcher;
    private FakeConnection conn;
    private FakeServerContext ctx;

    @BeforeEach
    void setUp() {
        dispatcher = new Dispatcher();
        ctx        = new FakeServerContext();
        dispatcher.setContext(ctx);
        conn       = new FakeConnection();
    }

    // -------------------------------------------------------------------------
    // Helper: a handler stub that records whether it was called
    // -------------------------------------------------------------------------

    private static class TrackingHandler implements CommandHandler {
        final String verb;
        final boolean requiresAuth;
        final Permission required;
        final RuntimeException toThrow;
        boolean called = false;

        TrackingHandler(String verb, boolean requiresAuth, Permission required, RuntimeException toThrow) {
            this.verb = verb;
            this.requiresAuth = requiresAuth;
            this.required = required;
            this.toThrow = toThrow;
        }

        @Override public String verb()          { return verb; }
        @Override public boolean requiresAuth() { return requiresAuth; }
        @Override public Permission required()  { return required; }

        @Override
        public void handle(Connection from, String args, ServerContext ctx) {
            called = true;
            if (toThrow != null) throw toThrow;
            from.send("OK");
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unknown command returns ERR UNKNOWN_COMMAND")
    void unknownCommandReturnsError() {
        dispatcher.dispatch(conn, "NOTAVERB");
        assertEquals(ErrorCode.UNKNOWN_COMMAND.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("Auth required: handler not called, ERR AUTH_REQUIRED returned")
    void authRequiredWhenNotAuthenticated() {
        TrackingHandler handler = new TrackingHandler("SECURE", true, Permission.NONE, null);
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "SECURE args");

        assertFalse(handler.called, "Handler must NOT be called");
        assertEquals(ErrorCode.AUTH_REQUIRED.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("Auth required: handler IS called when authenticated")
    void authPassesWhenAuthenticated() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));

        TrackingHandler handler = new TrackingHandler("SECURE", true, Permission.NONE, null);
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "SECURE");

        assertTrue(handler.called);
        assertEquals("OK", conn.lastSent());
    }

    @Test
    @DisplayName("Permission denied: ERR ACCESS_DENIED when auth().allowed() returns false")
    void permissionDenied() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));
        ctx.setAllowAll(false); // FakeServerContext: make all permission checks fail

        TrackingHandler handler = new TrackingHandler("UPLOAD", true, Permission.FILE_UPLOAD, null);
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "UPLOAD file.txt");

        assertFalse(handler.called, "Handler must NOT be called on ACCESS_DENIED");
        assertEquals(ErrorCode.ACCESS_DENIED.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("IllegalArgumentException from handler becomes ERR BAD_REQUEST")
    void illegalArgumentBecomesBAD_REQUEST() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));

        TrackingHandler handler = new TrackingHandler("CMD", true, Permission.NONE,
                new IllegalArgumentException("bad input"));
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "CMD bad input");

        assertTrue(handler.called);
        assertEquals(ErrorCode.BAD_REQUEST.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("RuntimeException from handler becomes ERR INTERNAL; connection stays open")
    void runtimeExceptionBecomesINTERNAL() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));

        TrackingHandler handler = new TrackingHandler("CMD", true, Permission.NONE,
                new RuntimeException("server bug"));
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "CMD");

        assertTrue(handler.called);
        assertEquals(ErrorCode.INTERNAL.toWire(), conn.lastSent());
        assertFalse(conn.isClosed(), "Connection must stay open after ERR INTERNAL");
    }

    @Test
    @DisplayName("Verb parsing: args are everything after the first space")
    void verbAndArgsParsedCorrectly() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));

        // Handler that echoes its args back
        dispatcher.register(new CommandHandler() {
            @Override public String verb()          { return "ECHO"; }
            @Override public boolean requiresAuth() { return false; }
            @Override public Permission required()  { return Permission.NONE; }
            @Override public void handle(Connection from, String args, ServerContext ctx) {
                from.send("ECHO " + args);
            }
        });

        dispatcher.dispatch(conn, "ECHO hello world");
        assertEquals("ECHO hello world", conn.lastSent());
    }

    @Test
    @DisplayName("Permission.NONE handler passes even when auth().allowed() returns false")
    void permissionNoneAlwaysPasses() {
        conn.session().authenticate(new Principal("alice", Role.STUDENT));
        ctx.setAllowAll(false);

        TrackingHandler handler = new TrackingHandler("PING", false, Permission.NONE, null);
        dispatcher.register(handler);

        dispatcher.dispatch(conn, "PING");

        assertTrue(handler.called, "NONE-permission handler must pass regardless of allowed()");
    }
}
