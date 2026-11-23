package campusnet.server.security;

import campusnet.api.Connection;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;
import campusnet.common.PropertiesConfig;
import campusnet.server.chat.core.ConnectionRegistryImpl;
import campusnet.server.chat.core.DefaultServerContext;
import campusnet.server.chat.core.Dispatcher;
import campusnet.server.chat.handlers.core.MsgHandler;
import campusnet.server.chat.handlers.ext.AuthHandler;
import campusnet.server.chat.handlers.ext.FileGetHandler;
import campusnet.server.chat.handlers.ext.FilePutHandler;
import campusnet.server.chat.handlers.ext.PrivateHandler;
import campusnet.testing.FakeConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration scenario IT3: Role-based access control.
 * Verifies that an EXTERNAL user is denied CHAT_PRIVATE, FILE_UPLOAD, and FILE_DOWNLOAD,
 * while STUDENT has full access.
 */
@Tag("integration")
class M5AccessControlIT {

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
        dispatcher.register(new MsgHandler());
        dispatcher.register(new PrivateHandler());
        dispatcher.register(new FilePutHandler());
        dispatcher.register(new FileGetHandler());
    }

    @Test
    @DisplayName("IT3: EXTERNAL user is denied PRIVATE and FILE_* commands but can broadcast")
    void externalUserDeniedPrivateAndFileTransfers() {
        // Connect and log in as alice (STUDENT)
        FakeConnection aliceConn = new FakeConnection();
        registry.add(aliceConn);
        dispatcher.dispatch(aliceConn, "AUTH alice pw1");
        assertEquals("OK AUTH alice STUDENT", aliceConn.lastSent());

        // Connect and log in as partner (EXTERNAL)
        FakeConnection partnerConn = new FakeConnection();
        registry.add(partnerConn);
        dispatcher.dispatch(partnerConn, "AUTH partner pw3");
        assertEquals("OK AUTH partner EXTERNAL", partnerConn.lastSent());

        // Partner sends broadcast MSG: allowed for EXTERNAL
        partnerConn.clearSent();
        dispatcher.dispatch(partnerConn, "MSG Hello campus network");
        // MsgHandler replies or broadcasts; no ACCESS_DENIED
        assertFalse(partnerConn.sentContaining(ErrorCode.ACCESS_DENIED.toWire()));

        // Partner attempts PRIVATE to alice: must receive ERR ACCESS_DENIED
        partnerConn.clearSent();
        dispatcher.dispatch(partnerConn, "PRIVATE alice Confidential info");
        assertEquals(ErrorCode.ACCESS_DENIED.toWire(), partnerConn.lastSent());

        // Partner attempts FILE_PUT: must receive ERR ACCESS_DENIED
        partnerConn.clearSent();
        dispatcher.dispatch(partnerConn, "FILE_PUT 1024 document.pdf");
        assertEquals(ErrorCode.ACCESS_DENIED.toWire(), partnerConn.lastSent());

        // Partner attempts FILE_GET: must receive ERR ACCESS_DENIED
        partnerConn.clearSent();
        dispatcher.dispatch(partnerConn, "FILE_GET document.pdf");
        assertEquals(ErrorCode.ACCESS_DENIED.toWire(), partnerConn.lastSent());

        // Conversely, alice (STUDENT) is allowed PRIVATE and FILE_PUT
        aliceConn.clearSent();
        dispatcher.dispatch(aliceConn, "PRIVATE partner Hello partner");
        assertEquals("OK PRIVATE_SENT", aliceConn.lastSent());

        aliceConn.clearSent();
        dispatcher.dispatch(aliceConn, "FILE_PUT 2048 project.zip");
        assertTrue(aliceConn.lastSent().startsWith("OK FILE_READY "));
    }
}
