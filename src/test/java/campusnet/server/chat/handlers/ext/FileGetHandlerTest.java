package campusnet.server.chat.handlers.ext;

import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.Protocol;
import campusnet.testing.FakeConnection;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileGetHandlerTest {

    private FileGetHandler handler;
    private FakeServerContext ctx;
    private FakeConnection conn;
    private Principal student;

    @BeforeEach
    void setUp() {
        handler = new FileGetHandler();
        ctx = new FakeServerContext();
        conn = new FakeConnection();
        student = new Principal("alice", Role.STUDENT);
        conn.session().authenticate(student);
    }

    @Test
    @DisplayName("Handler contract: verb is FILE_GET, requiresAuth is true, required is FILE_DOWNLOAD")
    void handlerContract() {
        assertEquals("FILE_GET", handler.verb());
        assertTrue(handler.requiresAuth());
        assertEquals(Permission.FILE_DOWNLOAD, handler.required());
    }

    @Test
    @DisplayName("Successful FILE_GET issues token with size 0 and replies OK FILE_READY <token> <port>")
    void successfulFileGet() {
        handler.handle(conn, "syllabus.pdf", ctx);

        String reply = conn.lastSent();
        assertNotNull(reply);
        assertTrue(reply.startsWith("OK FILE_READY "));

        String[] parts = reply.split(" ");
        assertEquals(4, parts.length);
        String token = parts[2];
        assertEquals(String.valueOf(Protocol.DEFAULT_FILE_PORT), parts[3]);

        Optional<Grant> grant = ctx.tokens().redeem(token);
        assertTrue(grant.isPresent());
        assertEquals("syllabus.pdf", grant.get().fileName());
        assertEquals(0, grant.get().size(), "GET grant must specify size 0");
        assertEquals(Op.GET, grant.get().op());
        assertEquals(student, grant.get().who());
    }

    @Test
    @DisplayName("File name containing spaces is preserved in GET token grant")
    void fileNameWithSpaces() {
        handler.handle(conn, "Lecture 01 Introduction to NIO.pdf", ctx);

        String reply = conn.lastSent();
        assertNotNull(reply);
        assertTrue(reply.startsWith("OK FILE_READY "));

        String token = reply.split(" ")[2];
        Optional<Grant> grant = ctx.tokens().redeem(token);
        assertTrue(grant.isPresent());
        assertEquals("Lecture 01 Introduction to NIO.pdf", grant.get().fileName());
        assertEquals(0, grant.get().size());
    }

    @Test
    @DisplayName("Invalid file names throw IllegalArgumentException")
    void invalidFileNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "../etc/passwd", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "dir/file.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "..", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, null, ctx));
    }
}
