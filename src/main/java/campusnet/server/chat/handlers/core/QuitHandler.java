package campusnet.server.chat.handlers.core;

import campusnet.api.*;

/** Sends OK BYE and closes the connection gracefully (M1). */
public class QuitHandler implements CommandHandler {
    @Override public String verb()          { return "QUIT"; }
    @Override public boolean requiresAuth() { return false; }
    @Override public Permission required()  { return Permission.NONE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        from.sendAndClose("OK BYE");
    }
}
