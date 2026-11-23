package campusnet.server.chat.handlers.ext;

import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.ErrorCode;
import campusnet.testing.FakeConnection;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrivateHandlerTest {

    private PrivateHandler handler;
    private FakeServerContext ctx;
    private FakeConnection sender;
    private FakeConnection recipient;

    @BeforeEach
    void setUp() {
        handler = new PrivateHandler();
        ctx = new FakeServerContext();

        sender = new FakeConnection();
        ctx.fakeRegistry().bind(sender, new Principal("alice", Role.STUDENT));

        recipient = new FakeConnection();
        ctx.fakeRegistry().bind(recipient, new Principal("bob", Role.LECTURER));
    }

    @Test
    @DisplayName("Handler contract: verb is PRIVATE, requiresAuth is true, required is CHAT_PRIVATE")
    void handlerContract() {
        assertEquals("PRIVATE", handler.verb());
        assertTrue(handler.requiresAuth());
        assertEquals(Permission.CHAT_PRIVATE, handler.required());
    }

    @Test
    @DisplayName("Successful private message relays to recipient and confirms to sender")
    void successfulPrivateMessage() {
        handler.handle(sender, "bob Hello Bob, how are you?", ctx);

        assertEquals("OK PRIVATE_SENT", sender.lastSent());
        assertEquals("PRIVATE from alice: Hello Bob, how are you?", recipient.lastSent());
    }

    @Test
    @DisplayName("Private message to non-existent user replies ERR NO_SUCH_USER")
    void recipientNotFound() {
        handler.handle(sender, "unknown_user Hello there", ctx);

        assertEquals(ErrorCode.NO_SUCH_USER.toWire(), sender.lastSent());
    }

    @Test
    @DisplayName("Missing arguments throw IllegalArgumentException (routed to ERR BAD_REQUEST)")
    void missingArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle(sender, null, ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(sender, "", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(sender, "bob", ctx)); // No message
        assertThrows(IllegalArgumentException.class, () -> handler.handle(sender, "   ", ctx));
    }
}
