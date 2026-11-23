package campusnet.server.chat.handlers.ext;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;

import java.util.Optional;

/**
 * Handles private messaging between authenticated users (M5).
 * Verb: PRIVATE user text
 * Delivers "PRIVATE from <sender>: <text>" to recipient and replies "OK PRIVATE_SENT" to sender.
 */
public class PrivateHandler implements CommandHandler {

    @Override
    public String verb() {
        return "PRIVATE";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public Permission required() {
        return Permission.CHAT_PRIVATE;
    }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        if (args == null || args.isBlank()) {
            throw new IllegalArgumentException("Usage: PRIVATE <user> <message>");
        }

        int space = args.indexOf(' ');
        if (space == -1) {
            throw new IllegalArgumentException("Usage: PRIVATE <user> <message>");
        }

        String targetUser = args.substring(0, space).trim();
        String text = args.substring(space + 1);

        if (targetUser.isEmpty() || text.isEmpty()) {
            throw new IllegalArgumentException("Target user and message must not be empty");
        }

        Optional<Connection> targetOpt = ctx.registry().findByUser(targetUser);
        if (targetOpt.isEmpty()) {
            from.send(ErrorCode.NO_SUCH_USER.toWire());
            return;
        }

        Connection target = targetOpt.get();
        String sender = from.session().principal().user();
        try {
            target.send("PRIVATE from " + sender + ": " + text);
        } catch (Exception ignored) {}

        from.send("OK PRIVATE_SENT");
    }
}
