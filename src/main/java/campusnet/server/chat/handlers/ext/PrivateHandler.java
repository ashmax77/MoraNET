package campusnet.server.chat.handlers.ext;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Sends a private message between users (M5). I0 stub — replies ERR NOT_IMPLEMENTED. */
public class PrivateHandler implements CommandHandler {
    @Override public String verb()          { return "PRIVATE"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.CHAT_PRIVATE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m5): find target by user, relay message, reply OK PRIVATE_SENT
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
