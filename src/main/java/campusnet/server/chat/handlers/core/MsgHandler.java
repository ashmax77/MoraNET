package campusnet.server.chat.handlers.core;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;

/** Broadcasts a chat message to all authenticated users (M1). */
public class MsgHandler implements CommandHandler {
    @Override public String verb()          { return "MSG"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.CHAT_BROADCAST; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        if (args == null || args.isBlank()) {
            from.send(ErrorCode.BAD_REQUEST.toWire());
            return;
        }
        String user = from.session().principal().user();
        ctx.registry().broadcast("MSG " + user + ": " + args);
    }
}
