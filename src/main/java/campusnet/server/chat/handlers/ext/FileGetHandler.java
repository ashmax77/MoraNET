package campusnet.server.chat.handlers.ext;

import campusnet.api.*;
import campusnet.common.ErrorCode;

/** Issues a download token and replies OK FILE_READY (M5). I0 stub — replies ERR NOT_IMPLEMENTED. */
public class FileGetHandler implements CommandHandler {
    @Override public String verb()          { return "FILE_GET"; }
    @Override public boolean requiresAuth() { return true; }
    @Override public Permission required()  { return Permission.FILE_DOWNLOAD; }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        // TODO(m5): validate name, issue token, reply OK FILE_READY token filePort
        from.send(ErrorCode.NOT_IMPLEMENTED.toWire());
    }
}
