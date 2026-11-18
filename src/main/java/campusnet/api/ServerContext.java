package campusnet.api;

/**
 * Gives handlers access to shared server services.
 * Passed to every CommandHandler.handle() call.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface ServerContext {
    Config config();
    ConnectionRegistry registry();
    AuthService auth();
    TokenService tokens();
}
