package campusnet.client.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParserTest {

    private CommandParser parser;

    @BeforeEach
    void setUp() {
        parser = new CommandParser();
    }

    @Test
    @DisplayName("Plain text becomes MSG broadcast frame")
    void testPlainTextBroadcast() {
        CommandParser.ParsedCommand cmd = parser.parse("Hello everyone!");
        assertEquals(CommandParser.CommandType.MSG, cmd.type());
        assertEquals("MSG Hello everyone!", cmd.wirePayload());
        assertTrue(cmd.isWireFrame());
        assertFalse(cmd.isEmpty());
        assertFalse(cmd.isInvalid());
    }

    @Test
    @DisplayName("Empty or null line produces EMPTY command")
    void testEmptyAndNull() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("Login with valid credentials produces AUTH frame")
    void testLoginValid() {
        CommandParser.ParsedCommand cmd = parser.parse("/login alice password123");
        assertEquals(CommandParser.CommandType.AUTH, cmd.type());
        assertEquals("AUTH alice password123", cmd.wirePayload());
        assertEquals("alice", cmd.primaryArg());
        assertEquals("password123", cmd.secondaryArg());
        assertTrue(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Login is case-insensitive for verb")
    void testLoginCaseInsensitive() {
        CommandParser.ParsedCommand cmd = parser.parse("/LOGIN bob secret");
        assertEquals(CommandParser.CommandType.AUTH, cmd.type());
        assertEquals("AUTH bob secret", cmd.wirePayload());
    }

    @Test
    @DisplayName("Login with missing password or missing arguments is INVALID")
    void testLoginMissingArgs() {
        CommandParser.ParsedCommand cmd1 = parser.parse("/login");
        assertTrue(cmd1.isInvalid());
        assertNotNull(cmd1.errorMessage());

        CommandParser.ParsedCommand cmd2 = parser.parse("/login alice");
        assertTrue(cmd2.isInvalid());
    }

    @Test
    @DisplayName("Private message with valid recipient and text produces PRIVATE frame")
    void testPrivateMessageValid() {
        CommandParser.ParsedCommand cmd = parser.parse("/pm bob How are you doing today?");
        assertEquals(CommandParser.CommandType.PRIVATE, cmd.type());
        assertEquals("PRIVATE bob How are you doing today?", cmd.wirePayload());
        assertEquals("bob", cmd.primaryArg());
        assertEquals("How are you doing today?", cmd.secondaryArg());
        assertTrue(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Private message missing arguments is INVALID")
    void testPrivateMessageInvalid() {
        assertTrue(parser.parse("/pm").isInvalid());
        assertTrue(parser.parse("/pm bob").isInvalid());
    }

    @Test
    @DisplayName("List online users produces LIST frame")
    void testListCommand() {
        CommandParser.ParsedCommand cmd = parser.parse("/list");
        assertEquals(CommandParser.CommandType.LIST, cmd.type());
        assertEquals("LIST", cmd.wirePayload());
        assertTrue(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Ping produces PING frame")
    void testPingCommand() {
        CommandParser.ParsedCommand cmd = parser.parse("/ping");
        assertEquals(CommandParser.CommandType.PING, cmd.type());
        assertEquals("PING", cmd.wirePayload());
        assertTrue(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Quit and Exit produce QUIT frame")
    void testQuitCommand() {
        CommandParser.ParsedCommand cmd1 = parser.parse("/quit");
        assertEquals(CommandParser.CommandType.QUIT, cmd1.type());
        assertEquals("QUIT", cmd1.wirePayload());

        CommandParser.ParsedCommand cmd2 = parser.parse("/exit");
        assertEquals(CommandParser.CommandType.QUIT, cmd2.type());
        assertEquals("QUIT", cmd2.wirePayload());
    }

    @Test
    @DisplayName("Info command triggers local network diagnostics")
    void testInfoCommand() {
        CommandParser.ParsedCommand cmd = parser.parse("/info");
        assertEquals(CommandParser.CommandType.INFO, cmd.type());
        assertFalse(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Discover command triggers local discovery")
    void testDiscoverCommand() {
        CommandParser.ParsedCommand cmd = parser.parse("/discover");
        assertEquals(CommandParser.CommandType.DISCOVER, cmd.type());
        assertFalse(cmd.isWireFrame());
    }

    @Test
    @DisplayName("Help command triggers help display")
    void testHelpCommand() {
        assertEquals(CommandParser.CommandType.HELP, parser.parse("/help").type());
        assertEquals(CommandParser.CommandType.HELP, parser.parse("/?").type());
    }

    @Test
    @DisplayName("Put command parses file path and strips quotes")
    void testPutCommand() {
        CommandParser.ParsedCommand cmd1 = parser.parse("/put my_file.pdf");
        assertEquals(CommandParser.CommandType.PUT, cmd1.type());
        assertEquals("my_file.pdf", cmd1.primaryArg());

        CommandParser.ParsedCommand cmd2 = parser.parse("/put \"C:\\My Documents\\lecture.pdf\"");
        assertEquals(CommandParser.CommandType.PUT, cmd2.type());
        assertEquals("C:\\My Documents\\lecture.pdf", cmd2.primaryArg());

        assertTrue(parser.parse("/put").isInvalid());
    }

    @Test
    @DisplayName("Get command parses remote file name and optional destination")
    void testGetCommand() {
        CommandParser.ParsedCommand cmd1 = parser.parse("/get lecture.pdf");
        assertEquals(CommandParser.CommandType.GET, cmd1.type());
        assertEquals("lecture.pdf", cmd1.primaryArg());
        assertNull(cmd1.secondaryArg());

        CommandParser.ParsedCommand cmd2 = parser.parse("/get lecture.pdf \"downloads/saved.pdf\"");
        assertEquals(CommandParser.CommandType.GET, cmd2.type());
        assertEquals("lecture.pdf", cmd2.primaryArg());
        assertEquals("downloads/saved.pdf", cmd2.secondaryArg());

        assertTrue(parser.parse("/get").isInvalid());
    }

    @Test
    @DisplayName("Unknown command starting with / returns INVALID with help tip")
    void testUnknownCommand() {
        CommandParser.ParsedCommand cmd = parser.parse("/unknown_command");
        assertTrue(cmd.isInvalid());
        assertNotNull(cmd.errorMessage());
        assertTrue(cmd.errorMessage().contains("Unknown command"));
    }
}
