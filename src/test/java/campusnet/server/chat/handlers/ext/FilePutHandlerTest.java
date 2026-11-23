package campusnet.server.chat.handlers.ext;

import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.ErrorCode;
import campusnet.common.PropertiesConfig;
import campusnet.testing.FakeConnection;
import campusnet.testing.FakeServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FilePutHandlerTest {

    private FilePutHandler handler;
    private FakeServerContext ctx;
    private FakeConnection conn;
    private Principal student;

    @BeforeEach
    void setUp() {
        handler = new FilePutHandler();
        ctx = new FakeServerContext();
        conn = new FakeConnection();
        student = new Principal("alice", Role.STUDENT);
        conn.session().authenticate(student);
    }

    @Test
    @DisplayName("Handler contract: verb is FILE_PUT, requiresAuth is true, required is FILE_UPLOAD")
    void handlerContract() {
        assertEquals("FILE_PUT", handler.verb());
        assertTrue(handler.requiresAuth());
        assertEquals(Permission.FILE_UPLOAD, handler.required());
    }

    @Test
    @DisplayName("Successful FILE_PUT issues token and replies OK FILE_READY <token> <port>")
    void successfulFilePut() {
        handler.handle(conn, "2048 project_code.zip", ctx);

        String reply = conn.lastSent();
        assertNotNull(reply);
        assertTrue(reply.startsWith("OK FILE_READY "));

        String[] parts = reply.split(" ");
        assertEquals(4, parts.length);
        String token = parts[2];
        assertEquals(String.valueOf(campusnet.common.Protocol.DEFAULT_FILE_PORT), parts[3]);

        Optional<Grant> grant = ctx.tokens().redeem(token);
        assertTrue(grant.isPresent());
        assertEquals("project_code.zip", grant.get().fileName());
        assertEquals(2048, grant.get().size());
        assertEquals(Op.PUT, grant.get().op());
        assertEquals(student, grant.get().who());
    }

    @Test
    @DisplayName("File name containing spaces is properly supported")
    void fileNameWithSpaces() {
        handler.handle(conn, "4096 Final Semester Project Report 2026.pdf", ctx);

        String reply = conn.lastSent();
        assertNotNull(reply);
        assertTrue(reply.startsWith("OK FILE_READY "));

        String token = reply.split(" ")[2];
        Optional<Grant> grant = ctx.tokens().redeem(token);
        assertTrue(grant.isPresent());
        assertEquals("Final Semester Project Report 2026.pdf", grant.get().fileName());
        assertEquals(4096, grant.get().size());
    }

    @Test
    @DisplayName("File size exceeding file.maxBytes replies ERR FILE_TOO_LARGE")
    void fileSizeExceedingMaxBytes() {
        Properties props = new Properties();
        props.setProperty("file.maxBytes", "1000");
        FakeServerContext customCtx = new FakeServerContext() {
            @Override
            public campusnet.api.Config config() {
                return new PropertiesConfig(props);
            }
        };

        handler.handle(conn, "2000 large_file.bin", customCtx);

        assertEquals(ErrorCode.FILE_TOO_LARGE.toWire(), conn.lastSent());
    }

    @Test
    @DisplayName("Invalid file names (path traversal, control chars, slashes) throw IllegalArgumentException")
    void invalidFileNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "1024 ../secret.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "1024 sub/folder/file.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "1024 sub\\folder\\file.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "1024 ..", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "1024 bad\u0001char.txt", ctx));
    }

    @Test
    @DisplayName("Invalid or negative size arguments throw IllegalArgumentException")
    void invalidSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "-1 file.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "not_a_number file.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "file_without_size.txt", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, "", ctx));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(conn, null, ctx));
    }
}
