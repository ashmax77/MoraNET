package campusnet.server.chat.handlers.ext;

import campusnet.api.CommandHandler;
import campusnet.api.Connection;
import campusnet.api.Permission;
import campusnet.api.Principal;
import campusnet.api.ServerContext;
import campusnet.common.ErrorCode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles client authentication commands (M5).
 * Verb: AUTH user pass
 * Verifies credentials, binds connection to principal in registry, and tracks failed attempts.
 */
public class AuthHandler implements CommandHandler {

    private static final System.Logger LOG = System.getLogger(AuthHandler.class.getName());
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final Map<Long, Integer> failedAttempts = new ConcurrentHashMap<>();

    @Override
    public String verb() {
        return "AUTH";
    }

    @Override
    public boolean requiresAuth() {
        return false;
    }

    @Override
    public Permission required() {
        return Permission.NONE;
    }

    @Override
    public void handle(Connection from, String args, ServerContext ctx) {
        if (from.session().authenticated()) {
            from.send(ErrorCode.ALREADY_LOGGED_IN.toWire());
            return;
        }

        if (args == null || args.isBlank()) {
            recordFailedAttempt(from);
            return;
        }

        int space = args.indexOf(' ');
        if (space == -1) {
            recordFailedAttempt(from);
            return;
        }

        String user = args.substring(0, space).trim();
        String pass = args.substring(space + 1);

        if (user.isEmpty()) {
            recordFailedAttempt(from);
            return;
        }

        Optional<Principal> opt = ctx.auth().authenticate(user, pass);
        if (opt.isEmpty()) {
            recordFailedAttempt(from);
            return;
        }

        Principal principal = opt.get();
        boolean bound = ctx.registry().bind(from, principal);
        if (!bound) {
            from.send(ErrorCode.ALREADY_LOGGED_IN.toWire());
            return;
        }

        failedAttempts.remove(from.id());
        from.send("OK AUTH " + principal.user() + " " + principal.role().name());
    }

    private void recordFailedAttempt(Connection from) {
        int attempts = failedAttempts.compute(from.id(), (k, v) -> (v == null ? 1 : v + 1));
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            failedAttempts.remove(from.id());
            LOG.log(System.Logger.Level.WARNING, "Connection {0} reached {1} failed auth attempts; closing connection",
                    from.id(), attempts);
            from.sendAndClose(ErrorCode.AUTH_FAILED.toWire());
        } else {
            from.send(ErrorCode.AUTH_FAILED.toWire());
        }
    }
}
