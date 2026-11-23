package campusnet.server.chat.core;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses incoming messages and routes them to the correct CommandHandler (M1).
 *
 * Guarantees given to every handler:
 * - requiresAuth() check: ERR AUTH_REQUIRED if not authenticated
 * - required() permission check: ERR ACCESS_DENIED if role lacks it
 * - IllegalArgumentException from handler -> ERR BAD_REQUEST
 * - Any other RuntimeException from handler -> ERR INTERNAL
 *
 * Constructor signature is FROZEN — do not change.
 */
public class Dispatcher {

    private final Map<String, CommandHandler> handlers = new HashMap<>();
    private ServerContext ctx;

    /** Used by ServerMain: creates Dispatcher before ServerContext exists. */
    public Dispatcher() {}

    /** Called by ServerMain after ServerContext is built. */
    public void setContext(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** Register a handler. Called at startup before any connections arrive. */
    public void register(CommandHandler handler) {
        handlers.put(handler.verb().toUpperCase(), handler);
    }

    /**
     * Dispatch a decoded payload to the appropriate handler.
     * @param from    the connection that sent the message
     * @param payload the full decoded frame payload (e.g. "MSG hello world")
     */
    public void dispatch(Connection from, String payload) {
        // Split at the first space: verb + args
        int space = payload.indexOf(' ');
        String verb = (space == -1 ? payload : payload.substring(0, space)).toUpperCase();
        String args = (space == -1 ? "" : payload.substring(space + 1));

        CommandHandler handler = handlers.get(verb);
        if (handler == null) {
            from.send(ErrorCode.UNKNOWN_COMMAND.toWire());
            return;
        }

        // Authentication check
        if (handler.requiresAuth() && !from.session().authenticated()) {
            from.send(ErrorCode.AUTH_REQUIRED.toWire());
            return;
        }

        // Permission check
        Permission perm = handler.required();
        if (perm != Permission.NONE && from.session().authenticated()) {
            if (!ctx.auth().allowed(from.session().principal().role(), perm)) {
                System.getLogger(getClass().getName()).log(
                        System.Logger.Level.WARNING,
                        "ACCESS_DENIED: user={0} role={1} verb={2} permission={3}",
                        from.session().principal().user(),
                        from.session().principal().role(),
                        verb,
                        perm);
                from.send(ErrorCode.ACCESS_DENIED.toWire());
                return;
            }
        }

        // Call handler
        try {
            handler.handle(from, args, ctx);
        } catch (IllegalArgumentException e) {
            from.send(ErrorCode.BAD_REQUEST.toWire());
        } catch (RuntimeException e) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,
                    "Handler {0} threw: {1}", verb, e.getMessage());
            from.send(ErrorCode.INTERNAL.toWire());
        }
    }
}
