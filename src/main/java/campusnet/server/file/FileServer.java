package campusnet.server.file;

import campusnet.api.ServerContext;
import campusnet.api.Service;
import campusnet.api.Grant;
import campusnet.api.Op;
import campusnet.common.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * File transfer server on TCP 5001 (M4).
 * Constructor signature is FROZEN — do not change.
 */
public class FileServer implements Service {

    private static final System.Logger LOG = System.getLogger(FileServer.class.getName());
    private static final int MAX_HEADER_BYTES = 256;

    private final ServerContext ctx;
    private volatile int port;
    private ServerSocketChannel serverChannel;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running;

    public FileServer(ServerContext ctx) {
        this.ctx = ctx;
    }

    @Override public String name() { return "file"; }

    @Override
    public void start() throws IOException {
        int configuredPort = ctx.config().getInt("file.port", Protocol.DEFAULT_FILE_PORT);
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(configuredPort));
        port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        running = true;
        pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "file-transfer-worker");
            thread.setDaemon(true);
            return thread;
        });
        acceptThread = new Thread(this::acceptLoop, "file-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.log(System.Logger.Level.INFO, "FileServer listening on port {0}", port);
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close file server channel: {0}", e.getMessage());
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.log(System.Logger.Level.INFO, "FileServer stopped.");
    }

    @Override
    public int localPort() { return port; }

    private void acceptLoop() {
        while (running && serverChannel.isOpen()) {
            try {
                SocketChannel channel = serverChannel.accept();
                if (channel == null) {
                    continue;
                }
                channel.configureBlocking(true);
                pool.execute(() -> handleConnection(channel));
            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING,
                            "FileServer accept error: {0}", e.getMessage());
                }
            }
        }
    }

    private void handleConnection(SocketChannel channel) {
        int timeoutMillis = ctx.config().getInt("file.idleTimeoutSec", 30) * 1000;
        try {
            channel.socket().setSoTimeout(timeoutMillis);
            String header = readHeader(channel);
            if (header == null) {
                return;
            }

            int space = header.indexOf(' ');
            if (space <= 0) {
                writeResponse(channel, "ERR BAD_REQUEST");
                return;
            }

            String verb = header.substring(0, space);
            String token = header.substring(space + 1).trim();
            if (token.isEmpty()) {
                writeResponse(channel, "ERR BAD_REQUEST");
                return;
            }

            java.util.Optional<Grant> redeemed = ctx.tokens().redeem(token);
            if (redeemed.isEmpty()) {
                writeResponse(channel, "ERR BAD_TOKEN");
                return;
            }

            Grant grant = redeemed.get();
            StorageService storage = new StorageService(ctx.config());
            if ("PUT".equals(verb) && grant.op() == Op.PUT) {
                long maxBytes = ctx.config().getInt("file.maxBytes", 52_428_800);
                new UploadTask(storage, channel, grant, maxBytes, timeoutMillis).run();
            } else if ("GET".equals(verb) && grant.op() == Op.GET) {
                new DownloadTask(storage, channel, grant, timeoutMillis).run();
            } else {
                writeResponse(channel, "ERR BAD_TOKEN");
                try {
                    channel.close();
                } catch (IOException closeError) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not close wrong-operation socket: {0}",
                            closeError.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "File connection failed: {0}", e.getMessage());
            try {
                writeResponse(channel, "ERR BAD_REQUEST");
                channel.close();
            } catch (IOException closeError) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close file socket: {0}", closeError.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "File connection rejected: {0}", e.getMessage());
            try {
                writeResponse(channel, "ERR BAD_REQUEST");
                channel.close();
            } catch (IOException closeError) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not close rejected file socket: {0}",
                        closeError.getMessage());
            }
        }
    }

    private String readHeader(SocketChannel channel) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1);
        StringBuilder header = new StringBuilder();
        while (header.length() <= MAX_HEADER_BYTES) {
            byteBuffer.clear();
            int read = channel.read(byteBuffer);
            if (read == -1) {
                return null;
            }
            if (read == 0) {
                continue;
            }
            byteBuffer.flip();
            char c = (char) (byteBuffer.get() & 0xff);
            if (c == '\n') {
                String line = header.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                return line;
            }
            if (header.length() >= MAX_HEADER_BYTES) {
                throw new IOException("File header exceeds 256 bytes");
            }
            header.append(c);
        }
        throw new IOException("File header exceeds 256 bytes");
    }

    private void writeResponse(SocketChannel channel, String response) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }
}
