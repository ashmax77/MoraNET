package campusnet.server.chat.handlers.ext;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Issues an upload token and replies OK FILE_READY (M5). I0 stub — replies ERR NOT_IMPLEMENTED. */
public class FilePutHandler implements CommandHandler {
    @Override public String verb()          { return "FILE_PUT"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.FILE_UPLOAD; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m5): validate args, issue token, reply OK FILE_READY token filePort
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
