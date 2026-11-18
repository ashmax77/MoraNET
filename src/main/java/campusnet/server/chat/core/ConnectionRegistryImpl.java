package campusnet.server.chat.core;

import campusnet.api.Connection;
import campusnet.api.ConnectionRegistry;
import campusnet.api.Principal;

import java.util.*;

/**
 * Thread-confined ConnectionRegistry (M1).
 * Accessed only from the selector thread — no synchronization needed.
 * I0 stub: minimal working implementation. Full implementation in milestone I1.
 */
public class ConnectionRegistryImpl implements ConnectionRegistry {

    private final Set<Connection> all = new LinkedHashSet<>();
    private final Map<String, Connection> byUser = new HashMap<>();

    /** Add a new connection (call immediately after accept). */
    public void add(Connection c) {
        all.add(c);
    }

    /** Remove a connection (call when closed). */
    public void remove(Connection c) {
        all.remove(c);
        if (c.session().authenticated()) {
            byUser.remove(c.session().principal().user());
        }
    }

    @Override
    public boolean bind(Connection c, Principal p) {
        if (byUser.containsKey(p.user())) return false;
        c.session().authenticate(p);
        byUser.put(p.user(), c);
        // Broadcast JOIN to all other authenticated connections
        String joinMsg = "JOIN " + p.user();
        for (Connection other : List.copyOf(byUser.values())) {
            if (other != c) {
                try { other.send(joinMsg); } catch (Exception ignored) {}
            }
        }
        return true;
    }

    @Override
    public Optional<Connection> findByUser(String user) {
        return Optional.ofNullable(byUser.get(user));
    }

    @Override
    public List<Connection> authenticated() {
        return List.copyOf(byUser.values());
    }

    @Override
    public void broadcast(String payload) {
        for (Connection c : List.copyOf(byUser.values())) {
            try { c.send(payload); } catch (Exception ignored) {}
        }
    }

    public int totalConnections() { return all.size(); }
    public List<String> onlineUsers() {
        List<String> users = new ArrayList<>(byUser.keySet());
        Collections.sort(users);
        return users;
    }
}
