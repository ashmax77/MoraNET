package campusnet.client.chat;

/**
 * Parses console input into client commands (M2).
 *
 * Supported commands:
 * - Plain text: sends MSG {@code <text>}
 * - /login {@code <user>} {@code <pass>}: sends AUTH {@code <user>} {@code <pass>}
 * - /pm {@code <user>} {@code <text>}: sends PRIVATE {@code <user>} {@code <text>}
 * - /list: sends LIST
 * - /put {@code <path>}: initiates file upload
 * - /get {@code <name>} [dest]: initiates file download
 * - /info: displays local network interface information
 * - /discover: discovers servers on the local network
 * - /ping: sends PING
 * - /quit: sends QUIT and exits
 * - /help: shows help menu
 */
public class CommandParser {

    /**
     * Types of commands produced by the parser.
     */
    public enum CommandType {
        MSG,
        AUTH,
        PRIVATE,
        LIST,
        PING,
        QUIT,
        PUT,
        GET,
        INFO,
        DISCOVER,
        HELP,
        EMPTY,
        INVALID
    }

    /**
     * Represents a parsed command outcome.
     *
     * @param type the type of command
     * @param wirePayload the formatted wire protocol payload to be sent (if applicable)
     * @param primaryArg the primary argument (e.g. file path for PUT, target user for PRIVATE, file name for GET)
     * @param secondaryArg the secondary argument (e.g. destination path for GET)
     * @param errorMessage the error message if the command syntax was invalid
     */
    public record ParsedCommand(
            CommandType type,
            String wirePayload,
            String primaryArg,
            String secondaryArg,
            String errorMessage
    ) {
        /**
         * Returns true if this command generates a standard chat server wire frame.
         */
        public boolean isWireFrame() {
            return type == CommandType.MSG
                    || type == CommandType.AUTH
                    || type == CommandType.PRIVATE
                    || type == CommandType.LIST
                    || type == CommandType.PING
                    || type == CommandType.QUIT;
        }

        /**
         * Returns true if the command was empty.
         */
        public boolean isEmpty() {
            return type == CommandType.EMPTY;
        }

        /**
         * Returns true if the command was invalid.
         */
        public boolean isInvalid() {
            return type == CommandType.INVALID;
        }
    }

    public static final String HELP_TEXT = """
            Available commands:
              <text>                  Send a broadcast chat message
              /login <user> <pass>    Log in with username and password
              /pm <user> <text>       Send a private message to a specific user
              /list                   List online users
              /put <path>             Upload a local file to the server
              /get <name> [dest]      Download a file from the server
              /info                   Show local network interface diagnostics
              /discover               Search for CampusNet servers on the LAN
              /ping                   Send a ping to the server
              /quit                   Disconnect and exit
              /help                   Show this help message""";

    /**
     * Parses a single line of console input into a {@link ParsedCommand}.
     *
     * @param rawLine the raw console line entered by the user
     * @return a structured {@link ParsedCommand}
     */
    public ParsedCommand parse(String rawLine) {
        if (rawLine == null) {
            return new ParsedCommand(CommandType.EMPTY, null, null, null, null);
        }

        String line = rawLine.trim();
        if (line.isEmpty()) {
            return new ParsedCommand(CommandType.EMPTY, null, null, null, null);
        }

        // Plain text message (not starting with '/')
        if (!line.startsWith("/")) {
            return new ParsedCommand(CommandType.MSG, "MSG " + rawLine, null, null, null);
        }

        // Split into command and arguments
        int firstSpace = line.indexOf(' ');
        String command = (firstSpace == -1 ? line.substring(1) : line.substring(1, firstSpace)).toLowerCase();
        String args = (firstSpace == -1 ? "" : line.substring(firstSpace + 1)).trim();

        return switch (command) {
            case "login" -> parseLogin(args);
            case "pm" -> parsePrivateMessage(args);
            case "list" -> new ParsedCommand(CommandType.LIST, "LIST", null, null, null);
            case "ping" -> new ParsedCommand(CommandType.PING, "PING", null, null, null);
            case "quit", "exit" -> new ParsedCommand(CommandType.QUIT, "QUIT", null, null, null);
            case "put" -> parsePut(args);
            case "get" -> parseGet(args);
            case "info" -> new ParsedCommand(CommandType.INFO, null, null, null, null);
            case "discover" -> new ParsedCommand(CommandType.DISCOVER, null, null, null, null);
            case "help", "?" -> new ParsedCommand(CommandType.HELP, null, null, null, null);
            default -> new ParsedCommand(
                    CommandType.INVALID,
                    null,
                    null,
                    null,
                    "Unknown command: /" + command + ". Type /help for available commands."
            );
        };
    }

    private ParsedCommand parseLogin(String args) {
        if (args.isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /login <user> <pass>");
        }
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /login <user> <pass>");
        }
        return new ParsedCommand(CommandType.AUTH, "AUTH " + parts[0] + " " + parts[1], parts[0], parts[1], null);
    }

    private ParsedCommand parsePrivateMessage(String args) {
        if (args.isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /pm <user> <text>");
        }
        int spaceIdx = args.indexOf(' ');
        if (spaceIdx == -1) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /pm <user> <text>");
        }
        String targetUser = args.substring(0, spaceIdx).trim();
        String messageText = args.substring(spaceIdx + 1).trim();
        if (targetUser.isEmpty() || messageText.isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /pm <user> <text>");
        }
        return new ParsedCommand(CommandType.PRIVATE, "PRIVATE " + targetUser + " " + messageText, targetUser, messageText, null);
    }

    private ParsedCommand parsePut(String args) {
        if (args.isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /put <path>");
        }
        // Strip surrounding quotes if user provided a quoted path
        String path = unquote(args);
        return new ParsedCommand(CommandType.PUT, null, path, null, null);
    }

    private ParsedCommand parseGet(String args) {
        if (args.isEmpty()) {
            return new ParsedCommand(CommandType.INVALID, null, null, null, "Usage: /get <name> [dest]");
        }
        String[] parts = args.split("\\s+", 2);
        String fileName = unquote(parts[0]);
        String destination = (parts.length > 1 && !parts[1].isBlank()) ? unquote(parts[1].trim()) : null;
        return new ParsedCommand(CommandType.GET, null, fileName, destination, null);
    }

    private String unquote(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }
}
