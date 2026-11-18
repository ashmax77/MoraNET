package campusnet.server.chat.handlers.core;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Returns the list of online usernames (M1). */
public class ListHandler implements CommandHandler {
    @Override public String verb()          { return "LIST"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.NONE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m1, #2): reply "USERS alice,bob,..."
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
