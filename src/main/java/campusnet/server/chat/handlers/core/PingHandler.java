package campusnet.server.chat.handlers.core;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.ServerContext;

/** Responds to PING with PONG (M1). No auth required — works as a keepalive. */
public class PingHandler implements CommandHandler {
    @Override public String verb()          { return "PING"; }
    @Override public boolean requiresAuth() { return false; }
    @Override public Permission required()  { return Permission.NONE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        from.send("PONG");
    }
}
