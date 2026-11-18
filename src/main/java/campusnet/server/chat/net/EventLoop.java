package campusnet.server.chat.net;

import campusnet.api.ServerContext;
import campusnet.server.chat.core.Dispatcher;

/**
 * The single-threaded NIO select loop (M1).
 * I0 stub: empty placeholder. Full implementation in milestone I1.
 */
public class EventLoop implements Runnable {

    private final ServerContext ctx;
    private final Dispatcher dispatcher;

    public EventLoop(ServerContext ctx, Dispatcher dispatcher) {
        this.ctx = ctx;
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {
        // TODO(m1, #1): implement select loop
    }
}
