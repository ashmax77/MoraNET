package campusnet.common;

import campusnet.api.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Config implementation backed by a Properties file.
 * Every getter takes a default value so a missing key never causes a NullPointerException.
 */
public final class PropertiesConfig implements Config {

    private final Properties props;

    public PropertiesConfig(Properties props) {
        this.props = props;
    }

    /**
     * Load from a file path.
     * @throws IOException if the file cannot be read
     */
    public static PropertiesConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        return new PropertiesConfig(p);
    }

    /**
     * Create a config with no properties (all getters return their defaults).
     * Used in tests that do not need a real config file.
     */
    public static PropertiesConfig empty() {
        return new PropertiesConfig(new Properties());
    }

    @Override
    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        String value = props.getProperty(key);
        if (value == null) return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public Path getPath(String key, String def) {
        return Path.of(props.getProperty(key, def));
    }
}
