package campusnet.api;

import java.nio.file.Path;

/**
 * Read-only configuration access.
 * Every method has a default parameter so a missing key never crashes a service.
 * PropertiesConfig in campusnet.common is the production implementation.
 * FROZEN — do not change without following the contract change procedure.
 */
public interface Config {
    /** Returns the value for key, or def if the key is absent. */
    String get(String key, String def);

    /** Returns the integer value for key, or def if the key is absent or not a number. */
    int getInt(String key, int def);

    /** Returns the value for key as a Path, or Path.of(def) if the key is absent. */
    Path getPath(String key, String def);
}
