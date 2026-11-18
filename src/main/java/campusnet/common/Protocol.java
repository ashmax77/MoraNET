package campusnet.common;

/**
 * Frozen protocol constants and pure utility functions.
 * No instances — utility class only.
 */
public final class Protocol {

    private Protocol() {}

    /** Protocol version string sent in WELCOME. */
    public static final String VERSION = "campusnet/1";

    /** Default chat port. */
    public static final int DEFAULT_CHAT_PORT = 5000;

    /** Default file transfer port. */
    public static final int DEFAULT_FILE_PORT = 5001;

    /** Default UDP discovery port. */
    public static final int DEFAULT_DISCOVERY_PORT = 8888;

    /** Number of bytes in a frame length header (4-byte big-endian int). */
    public static final int HEADER_SIZE = 4;

    /** Maximum UTF-8 payload size in bytes. */
    public static final int MAX_PAYLOAD = 4096;

    /** Minimum UTF-8 payload size in bytes. */
    public static final int MIN_PAYLOAD = 1;

    /**
     * Returns true if the file name is acceptable for flat storage.
     *
     * Rules (pure function — no I/O):
     * - Length: 1 to 255 characters (after trimming is NOT done — the name must already be clean).
     * - No '/' character.
     * - No '\' character.
     * - Not equal to ".." or starting with "../" or "..\" (path traversal guard).
     * - No control characters (codepoints 0-31 or 127).
     *
     * This function validates only the name string.
     * The FileServer must also run Paths.get(name).getFileName() and
     * check normalize().startsWith(storageRoot) for path traversal.
     *
     * @param name the raw file name from the client
     * @return true if the name is acceptable
     */
    public static boolean isValidFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            return false;
        }
        if (name.contains("/") || name.contains("\\")) {
            return false;
        }
        if (name.equals("..") || name.startsWith("../") || name.startsWith("..\\")) {
            return false;
        }
        // No control characters
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127) {
                return false;
            }
        }
        return true;
    }
}
