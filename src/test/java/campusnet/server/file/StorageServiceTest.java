package campusnet.server.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesValidFlatFileName() throws Exception {
        StorageService storage = new StorageService(tempDir);

        Path resolved = storage.resolveName("report.pdf");

        assertEquals(tempDir.resolve("report.pdf").normalize(), resolved);
    }

    @Test
    void rejectsTraversalAbsoluteAndControlNames() {
        StorageService storage = new StorageService(tempDir);

        assertThrows(IllegalArgumentException.class, () -> storage.resolveName("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName("sub/file.txt"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName("bad\\name.txt"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName("bad\u0000name"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName(""));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> storage.resolveName(Path.of(tempDir.toString(), "absolute.txt").toString()));
    }

    @Test
    void createsAndReturnsStorageRoot() throws Exception {
        Path root = tempDir.resolve("nested/storage");
        StorageService storage = new StorageService(root);

        storage.createStorageRoot();

        assertTrue(Files.isDirectory(root));
        assertEquals(root.toAbsolutePath().normalize(), storage.storageRoot());
    }

    @Test
    void createsPartNameBesideFinalName() {
        StorageService storage = new StorageService(tempDir);

        Path part = storage.resolvePartName("report.pdf");

        assertEquals(tempDir.resolve("report.pdf.part").normalize(), part);
        assertFalse(part.equals(storage.resolveName("report.pdf")));
    }
}
