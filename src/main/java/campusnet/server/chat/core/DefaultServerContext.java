package campusnet.server.chat.core;

import campusnet.api.*;

/**
 * Default ServerContext implementation — a simple value holder (M1).
 * Wired by ServerMain. Passed to every handler via Dispatcher.
 */
public class DefaultServerContext implements ServerContext {

    private final Config config;
    private final ConnectionRegistry registry;
    private final AuthService auth;
    private final TokenService tokens;

    public DefaultServerContext(Config config, ConnectionRegistry registry,
                                 AuthService auth, TokenService tokens) {
        this.config = config;
        this.registry = registry;
        this.auth = auth;
        this.tokens = tokens;
    }

    @Override public Config config()              { return config; }
    @Override public ConnectionRegistry registry() { return registry; }
    @Override public AuthService auth()            { return auth; }
    @Override public TokenService tokens()         { return tokens; }
}
