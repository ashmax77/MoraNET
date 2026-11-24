package campusnet.server.chat.handlers.ext;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Op;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.ServerContext;
import campusnet.common.Protocol;

/**
 * Handles file download requests and issues one-time download tokens (M5).
 * Verb: FILE_GET name
 * Validates file name, issues a GET token (size 0), and replies "OK FILE_READY <token> <port>".
 */
public class FileGetHandler implements CommandHandler {

    @Override
    public String verb() {
        return "FILE_GET";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public Permission required() {
        return Permission.FILE_DOWNLOAD;
    }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("Usage: FILE_GET <name>");
        }

        String fileName = args;

        if (!Protocol.isValidFileName(fileName)) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }

        Principal who = from.session().principal();
        String token = ctx.tokens().issue(who, Op.GET, fileName, 0L);
        int filePort = ctx.config() != null ? ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT) : Protocol.DEFAULT_FILE_PORT;

        from.send("OK FILE_READY " + token + " " + filePort);
    }
}
