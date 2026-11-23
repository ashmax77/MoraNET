package campusnet.server.chat.handlers.core;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;
import campusnet.server.chat.core.ConnectionRegistryImpl;

/** Returns the list of online usernames (M1). */
public class ListHandler implements CommandHandler {
    @Override public String verb()          { return "LIST"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.NONE; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        ConnectionRegistryImpl registry = (ConnectionRegistryImpl) ctx.registry();
        String users = String.join(",", registry.onlineUsers());
        from.send("USERS " + users);
    }
}
