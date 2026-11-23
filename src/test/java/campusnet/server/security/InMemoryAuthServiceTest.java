package campusnet.server.security;

import campusnet.api.Config;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.Role;
import campusnet.common.PropertiesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAuthServiceTest {

    private InMemoryAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new InMemoryAuthService(PropertiesConfig.empty());
    }

    // ── Permission Matrix (All 12 Cells) ────────────────────────────────────

    @Test
    @DisplayName("Permission matrix: STUDENT has access to all four permissions")
    void studentPermissions() {
        assertTrue(authService.allowed(Role.STUDENT, Permission.CHAT_BROADCAST));
        assertTrue(authService.allowed(Role.STUDENT, Permission.CHAT_PRIVATE));
        assertTrue(authService.allowed(Role.STUDENT, Permission.FILE_UPLOAD));
        assertTrue(authService.allowed(Role.STUDENT, Permission.FILE_DOWNLOAD));
    }

    @Test
    @DisplayName("Permission matrix: LECTURER has access to all four permissions")
    void lecturerPermissions() {
        assertTrue(authService.allowed(Role.LECTURER, Permission.CHAT_BROADCAST));
        assertTrue(authService.allowed(Role.LECTURER, Permission.CHAT_PRIVATE));
        assertTrue(authService.allowed(Role.LECTURER, Permission.FILE_UPLOAD));
        assertTrue(authService.allowed(Role.LECTURER, Permission.FILE_DOWNLOAD));
    }

    @Test
    @DisplayName("Permission matrix: EXTERNAL allowed only CHAT_BROADCAST, denied others")
    void externalPermissions() {
        assertTrue(authService.allowed(Role.EXTERNAL, Permission.CHAT_BROADCAST));
        assertFalse(authService.allowed(Role.EXTERNAL, Permission.CHAT_PRIVATE));
        assertFalse(authService.allowed(Role.EXTERNAL, Permission.FILE_UPLOAD));
        assertFalse(authService.allowed(Role.EXTERNAL, Permission.FILE_DOWNLOAD));
    }

    @Test
    @DisplayName("Permission matrix: Permission.NONE is allowed for all roles")
    void permissionNoneAllowedForAll() {
        assertTrue(authService.allowed(Role.STUDENT, Permission.NONE));
        assertTrue(authService.allowed(Role.LECTURER, Permission.NONE));
        assertTrue(authService.allowed(Role.EXTERNAL, Permission.NONE));
    }

    @Test
    @DisplayName("Permission matrix: null role or null permission returns false")
    void nullRoleOrPermissionReturnsFalse() {
        assertFalse(authService.allowed(null, Permission.CHAT_BROADCAST));
        assertFalse(authService.allowed(Role.STUDENT, null));
        assertFalse(authService.allowed(null, null));
    }

    // ── Authentication with Default Demo Users ──────────────────────────────

    @Test
    @DisplayName("Default user alice authenticates with role STUDENT")
    void authenticateAliceSuccess() {
        Optional<Principal> opt = authService.authenticate("alice", "pw1");
        assertTrue(opt.isPresent());
        assertEquals("alice", opt.get().user());
        assertEquals(Role.STUDENT, opt.get().role());
    }

    @Test
    @DisplayName("Default user bob authenticates with role LECTURER")
    void authenticateBobSuccess() {
        Optional<Principal> opt = authService.authenticate("bob", "pw2");
        assertTrue(opt.isPresent());
        assertEquals("bob", opt.get().user());
        assertEquals(Role.LECTURER, opt.get().role());
    }

    @Test
    @DisplayName("Default user partner authenticates with role EXTERNAL")
    void authenticatePartnerSuccess() {
        Optional<Principal> opt = authService.authenticate("partner", "pw3");
        assertTrue(opt.isPresent());
        assertEquals("partner", opt.get().user());
        assertEquals(Role.EXTERNAL, opt.get().role());
    }

    @Test
    @DisplayName("Authentication fails for wrong password")
    void authenticateWrongPasswordFails() {
        Optional<Principal> opt = authService.authenticate("alice", "wrong_password");
        assertTrue(opt.isEmpty());
    }

    @Test
    @DisplayName("Authentication fails for non-existent user")
    void authenticateUnknownUserFails() {
        Optional<Principal> opt = authService.authenticate("unknown_user", "some_password");
        assertTrue(opt.isEmpty());
    }

    @Test
    @DisplayName("Authentication fails for null or empty credentials")
    void authenticateNullOrEmptyFails() {
        assertTrue(authService.authenticate(null, "pw1").isEmpty());
        assertTrue(authService.authenticate("", "pw1").isEmpty());
        assertTrue(authService.authenticate("alice", null).isEmpty());
    }

    // ── Authentication with Custom Config Users ─────────────────────────────

    @Test
    @DisplayName("Custom users configured via Config are properly authenticated")
    void authenticateCustomConfigUsers() {
        Properties props = new Properties();
        props.setProperty("user.charlie", "secret123,STUDENT");
        props.setProperty("user.drsmith", "lecture456,LECTURER");
        props.setProperty("user.visitor", "guest789,EXTERNAL");

        Config customConfig = new PropertiesConfig(props);
        InMemoryAuthService customAuth = new InMemoryAuthService(customConfig);

        Optional<Principal> charlie = customAuth.authenticate("charlie", "secret123");
        assertTrue(charlie.isPresent());
        assertEquals(Role.STUDENT, charlie.get().role());

        Optional<Principal> smith = customAuth.authenticate("drsmith", "lecture456");
        assertTrue(smith.isPresent());
        assertEquals(Role.LECTURER, smith.get().role());

        Optional<Principal> visitor = customAuth.authenticate("visitor", "guest789");
        assertTrue(visitor.isPresent());
        assertEquals(Role.EXTERNAL, visitor.get().role());
    }
}
