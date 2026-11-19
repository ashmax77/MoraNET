package campusnet.api;

/**
 * A handler for one chat command verb (e.g. MSG, LIST, AUTH, PING).
 *
 * Rules for every implementation:
 * - Never block (no Thread.sleep, no blocking I/O, no locks held across calls).
 * - Throw IllegalArgumentException for bad arguments — Dispatcher turns it into ERR BAD_REQUEST.
 * - Throw nothing else — Dispatcher turns any other RuntimeException into ERR INTERNAL.
 * - Do NOT repeat the auth/permission checks — Dispatcher already does them.
 *
 * FROZEN — do not change without following the contract change procedure.
 */
public interface CommandHandler {
    /** Upper-case verb this handler responds to, e.g. "MSG". */
    String verb();

    /** If true, Dispatcher rejects the command with ERR AUTH_REQUIRED when not authenticated. */
    boolean requiresAuth();

    /**
     * Required permission, or Permission.NONE if authentication alone is enough.
     * Dispatcher rejects with ERR ACCESS_DENIED when auth().allowed(role, required()) is false.
     */
    Permission required();

    /**
     * Handle the command on the selector thread. Must not block.
     * @param from  the connection that sent the command
     * @param args  everything after the first space in the payload (may be empty string)
     * @param ctx   access to registry, auth, tokens, config
     */
    void handle(Connection from, String args, ServerContext ctx);
}
