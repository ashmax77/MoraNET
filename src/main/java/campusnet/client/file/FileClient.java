package campusnet.client.file;

import campusnet.api.FileTransferClient;
import campusnet.api.ServerInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * File transfer client (M4).
 * Constructor signature is FROZEN — do not change.
 */
public class FileClient implements FileTransferClient {

    public FileClient() {}

    @Override
    public void upload(ServerInfo server, String token, Path file) throws IOException {
        if (server == null) throw new IOException("Server information is required");
        if (token == null || token.isBlank()) throw new IOException("Transfer token is required");
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Upload file does not exist: " + file);
        }

        try (SocketChannel channel = SocketChannel.open()) {
            channel.socket().setSoTimeout(30_000);
            channel.connect(new InetSocketAddress(server.host(), server.filePort()));
            writeHeader(channel, "PUT " + token);

            try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ)) {
                long size = source.size();
                long transferred = 0;
                while (transferred < size) {
                    long moved = source.transferTo(transferred, size - transferred, channel);
                    if (moved <= 0) {
                        throw new IOException("Upload stalled after " + transferred + " of " + size + " bytes");
                    }
                    transferred += moved;
                }
            }

            String response = readResponseLine(channel);
            if (!"OK".equals(response)) {
                throw new IOException("Upload rejected: " + response);
            }
        }
    }

    @Override
    public void download(ServerInfo server, String token, Path destination) throws IOException {
        if (server == null) throw new IOException("Server information is required");
        if (token == null || token.isBlank()) throw new IOException("Transfer token is required");
        if (destination == null) throw new IOException("Download destination is required");

        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path parent = absoluteDestination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Path.of(absoluteDestination.toString() + ".download");

        try (SocketChannel channel = SocketChannel.open()) {
            channel.socket().setSoTimeout(30_000);
            channel.connect(new InetSocketAddress(server.host(), server.filePort()));
            writeHeader(channel, "GET " + token);

            String response = readResponseLine(channel);
            if (response == null || !response.startsWith("OK ")) {
                throw new IOException("Download rejected: " + response);
            }

            long size;
            try {
                size = Long.parseLong(response.substring(3).trim());
            } catch (NumberFormatException e) {
                throw new IOException("Malformed download size: " + response, e);
            }
            if (size < 0) {
                throw new IOException("Malformed download size: " + size);
            }

            Files.deleteIfExists(temp);
            try (FileChannel output = FileChannel.open(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                long transferred = 0;
                while (transferred < size) {
                    ByteBuffer buffer = ByteBuffer.allocate(
                            (int) Math.min(64 * 1024, size - transferred));
                    int read = channel.read(buffer);
                    if (read == -1) {
                        throw new IOException("Download truncated: expected " + size
                                + " bytes but received " + transferred);
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        transferred += output.write(buffer);
                    }
                }
            }

            try {
                Files.move(temp, absoluteDestination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic move is required for download completion", e);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupError) {
                throw new IOException("Download failed and temporary file could not be deleted", cleanupError);
            }
            throw e;
        }
    }

    private void writeHeader(SocketChannel channel, String header) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap((header + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private String readResponseLine(SocketChannel channel) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1);
        StringBuilder response = new StringBuilder();
        while (response.length() <= 256) {
            byteBuffer.clear();
            int read = channel.read(byteBuffer);
            if (read == -1) {
                throw new IOException("File server closed the connection");
            }
            if (read == 0) {
                continue;
            }
            byteBuffer.flip();
            char c = (char) (byteBuffer.get() & 0xff);
            if (c == '\n') {
                String line = response.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                return line;
            }
            response.append(c);
        }
        throw new IOException("File response exceeds 256 bytes");
    }
}
