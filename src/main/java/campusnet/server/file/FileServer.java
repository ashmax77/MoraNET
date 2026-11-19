package campusnet.server.file;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.common.Protocol;

import java.io.IOException;

/**
 * File transfer server on TCP 5001 (M4).
 * I0 stub: start() does nothing; stop() does nothing.
 * Constructor signature is FROZEN — do not change.
 */
public class FileServer implements Service {

    private final ServerContext ctx;
    private volatile int port;

    public FileServer(ServerContext ctx) {
        this.ctx = ctx;
    }

    @Override public String name() { return "file"; }

    @Override
    public void start() throws IOException {
        // TODO(m4): bind ServerSocketChannel, start accept thread and thread pool
        port = ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                "FileServer stub started (not yet accepting connections)");
    }

    @Override
    public void stop() {
        // TODO(m4): close server channel and shut down thread pool
    }

    @Override
    public int localPort() { return port; }
}
