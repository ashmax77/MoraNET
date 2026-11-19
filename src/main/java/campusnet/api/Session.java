package campusnet.api;

/**
 * Mutable per-connection authentication state.
 * Touched ONLY on the chat selector thread — no synchronization needed.
 *
 * Lifecycle:
 *   new Session()          -> not authenticated, principal() returns null
 *   authenticate(p)        -> authenticated, principal() returns p
 *   authenticated() == true once and stays true forever
 */
public final class Session {

    private Principal principal;

    public Session() {
        this.principal = null;
    }

    /** Returns the authenticated principal, or null if not yet authenticated. */
    public Principal principal() {
        return principal;
    }

    /** Returns true if the user has authenticated. */
    public boolean authenticated() {
        return principal != null;
    }

    /**
     * Marks this session as authenticated.
     * @throws IllegalStateException if already authenticated.
     */
    public void authenticate(Principal p) {
        if (this.principal != null) {
            throw new IllegalStateException("Already authenticated as " + principal.user());
        }
        if (p == null) throw new IllegalArgumentException("Principal must not be null");
        this.principal = p;
    }
}
