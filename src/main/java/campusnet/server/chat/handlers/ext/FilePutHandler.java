package campusnet.server.chat.handlers.ext;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Op;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;
import campusnet.common.Protocol;

/**
 * Handles file upload requests and issues one-time upload tokens (M5).
 * Verb: FILE_PUT size name
 * Validates file name and size limits, issues a PUT token, and replies "OK FILE_READY <token> <port>".
 */
public class FilePutHandler implements CommandHandler {

    private static final int DEFAULT_MAX_FILE_BYTES = 52428800; // 50 MB

    @Override
    public String verb() {
        return "FILE_PUT";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public Permission required() {
        return Permission.FILE_UPLOAD;
    }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("Usage: FILE_PUT <size> <name>");
        }

        int space = args.indexOf(' ');
        if (space == -1) {
            throw new IllegalArgumentException("Usage: FILE_PUT <size> <name>");
        }

        String sizeStr = args.substring(0, space).trim();
        String fileName = args.substring(space + 1);

        long size;
        try {
            size = Long.parseLong(sizeStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid file size: " + sizeStr);
        }

        if (size < 0) {
            throw new IllegalArgumentException("File size cannot be negative: " + size);
        }

        if (!Protocol.isValidFileName(fileName)) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }

        long maxBytes = ctx.config() != null ? ctx.config().getInt("file.maxBytes", DEFAULT_MAX_FILE_BYTES) : DEFAULT_MAX_FILE_BYTES;
        if (size > maxBytes) {
            from.send(ErrorCode.FILE_TOO_LARGE.toWire());
            return;
        }

        Principal who = from.session().principal();
        String token = ctx.tokens().issue(who, Op.PUT, fileName, size);
        int filePort = ctx.config() != null ? ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT) : Protocol.DEFAULT_FILE_PORT;

        from.send("OK FILE_READY " + token + " " + filePort);
    }
}
