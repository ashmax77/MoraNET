package campusnet.server.chat.handlers.ext;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Authenticates users (M5). I0 stub — replies ERR NOT_IMPLEMENTED. */
public class AuthHandler implements CommandHandler {
    @Override public String verb()          { return "AUTH"; }
    @Override public boolean requiresAuth() { return false; }
    @Override public Permission required()  { return Permission.NONE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m5): authenticate, call registry.bind(), reply OK AUTH user ROLE
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
