package campusnet.server.file;

import campusnet.api.Config;
import campusnet.common.Protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves file names beneath the configured flat storage directory.
 *
 * <p>All name validation and path resolution is centralized here so upload and
 * download tasks cannot escape the storage root.
 */
public final class StorageService {

    private static final String DEFAULT_STORAGE_DIR = "storage";
    private final Path storageRoot;

    public StorageService(Config config) {
        Path configured = config != null
                ? config.getPath("file.storageDir", DEFAULT_STORAGE_DIR)
                : Path.of(DEFAULT_STORAGE_DIR);
        this.storageRoot = configured.toAbsolutePath().normalize();
    }

    public StorageService(Path storageRoot) {
        if (storageRoot == null) {
            throw new IllegalArgumentException("Storage root must not be null");
        }
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    public Path storageRoot() {
        return storageRoot;
    }

    public void createStorageRoot() throws IOException {
        Files.createDirectories(storageRoot);
    }

    public Path resolveName(String name) {
        if (!Protocol.isValidFileName(name)) {
            throw new IllegalArgumentException("Invalid file name");
        }

        Path rootCandidate = Path.of(name);
        if (rootCandidate.isAbsolute()) {
            throw new IllegalArgumentException("Absolute file names are not allowed");
        }

        Path resolved = storageRoot.resolve(name).normalize();
        if (!resolved.getParent().equals(storageRoot)) {
            throw new IllegalArgumentException("File name resolves outside the storage directory");
        }
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("File name resolves outside the storage directory");
        }
        return resolved;
    }

    public Path resolvePartName(String name) {
        return Path.of(resolveName(name).toString() + ".part");
    }
}
