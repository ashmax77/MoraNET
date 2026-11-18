package campusnet.server.chat.handlers.core;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Broadcasts a chat message to all authenticated users (M1). */
public class MsgHandler implements CommandHandler {
    @Override public String verb()          { return "MSG"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.CHAT_BROADCAST; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m1, #2): broadcast "MSG user: args" to registry.authenticated()
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
