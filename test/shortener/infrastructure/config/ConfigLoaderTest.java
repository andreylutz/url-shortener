package shortener.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {
    private static final String VALID_CONFIG =
            """
            link.ttl.seconds=3600
            link.default-limit=5
            cleanup.interval.seconds=10
            link.prefix=https://short.example/Links/
            link.code.length=10
            storage.path=data/links.json
            """;

    @TempDir Path temporaryDirectory;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void loadsAllSettingsFromSelectedFile() throws IOException {
        AppConfig config = loader.load(writeProperties(validProperties()));

        assertAll(
                () -> assertEquals(Duration.ofHours(1), config.linkTtl()),
                () -> assertEquals(5, config.defaultVisitLimit()),
                () -> assertEquals(Duration.ofSeconds(10), config.cleanupInterval()),
                () -> assertEquals("https://short.example/Links/", config.shortLinkPrefix()),
                () -> assertEquals(10, config.codeLength()),
                () -> assertEquals(Path.of("data/links.json"), config.storagePath()));
    }

    @Test
    void loadsRepositoryConfiguration() {
        AppConfig config = loader.load(Path.of("config/application.properties"));

        assertAll(
                () -> assertEquals(Duration.ofDays(1), config.linkTtl()),
                () -> assertEquals(100, config.defaultVisitLimit()),
                () -> assertEquals(Duration.ofMinutes(1), config.cleanupInterval()),
                () -> assertEquals("https://short.local/", config.shortLinkPrefix()),
                () -> assertEquals(8, config.codeLength()),
                () -> assertEquals(Path.of("data/storage.json"), config.storagePath()));
    }

    @Test
    void readsUtf8Paths() throws IOException {
        Properties properties = validProperties();
        properties.setProperty("storage.path", "данные/ссылки.json");

        AppConfig config = loader.load(writeProperties(properties));

        assertEquals(Path.of("данные/ссылки.json"), config.storagePath());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "link.ttl.seconds", "link.default-limit", "cleanup.interval.seconds",
                "link.prefix", "link.code.length", "storage.path"
            })
    void rejectsMissingSettingsWithoutUsingDefaults(String key) throws IOException {
        Properties properties = validProperties();
        properties.remove(key);

        assertInvalidSetting(properties, key);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "link.ttl.seconds", "link.default-limit", "cleanup.interval.seconds",
                "link.prefix", "link.code.length", "storage.path"
            })
    void rejectsBlankSettings(String key) throws IOException {
        Properties properties = validProperties();
        properties.setProperty(key, "   ");

        assertInvalidSetting(properties, key);
    }

    @ParameterizedTest
    @CsvSource({
        "link.ttl.seconds, 1.5",
        "link.ttl.seconds, 9223372036854775808",
        "cleanup.interval.seconds, минута",
        "link.default-limit, пять",
        "link.default-limit, 2147483648",
        "link.code.length, 1.5"
    })
    void rejectsMalformedAndOverflowingNumbers(String key, String value) throws IOException {
        Properties properties = validProperties();
        properties.setProperty(key, value);

        assertInvalidSetting(properties, key);
    }

    @ParameterizedTest
    @CsvSource({
        "link.ttl.seconds, 0",
        "link.ttl.seconds, -1",
        "cleanup.interval.seconds, 0",
        "cleanup.interval.seconds, -1",
        "link.default-limit, 0",
        "link.default-limit, -1",
        "link.code.length, 0",
        "link.code.length, -1",
        "link.code.length, 65"
    })
    void rejectsValuesOutsideAllowedRanges(String key, String value) throws IOException {
        Properties properties = validProperties();
        properties.setProperty(key, value);

        assertInvalidSetting(properties, key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"link.ttl.seconds", "cleanup.interval.seconds"})
    void rejectsDurationsThatOverflowMilliseconds(String key) throws IOException {
        Properties properties = validProperties();
        properties.setProperty(key, Long.toString(Long.MAX_VALUE));

        assertInvalidSetting(properties, key);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "short/",
                "ftp://short.example/",
                "https:///",
                "https://short.example",
                "https://short.example/a b/",
                "https://short.example/?query=1",
                "https://short.example/#fragment",
                "https://user:password@short.example/",
                "https://short.example:0/",
                "https://short.example:65536/"
            })
    void rejectsInvalidPrefixes(String prefix) throws IOException {
        Properties properties = validProperties();
        properties.setProperty("link.prefix", prefix);

        assertInvalidSetting(properties, "link.prefix");
    }

    @Test
    void rejectsInvalidStoragePath() throws IOException {
        Properties properties = validProperties();
        properties.setProperty("storage.path", "data/\u0000links.json");

        assertInvalidSetting(properties, "storage.path");
    }

    @Test
    void rejectsDirectoryInsteadOfStorageFile() throws IOException {
        Properties properties = validProperties();
        properties.setProperty("storage.path", temporaryDirectory.toString());

        assertInvalidSetting(properties, "storage.path");
    }

    @Test
    void reportsMissingConfigurationFile() {
        Path path = temporaryDirectory.resolve("missing.properties");

        ConfigurationException exception =
                assertThrows(ConfigurationException.class, () -> loader.load(path));

        assertTrue(exception.getMessage().contains(path.toString()));
        assertNotNull(exception.getCause());
    }

    @Test
    void reportsDirectoryInsteadOfConfigurationFile() {
        ConfigurationException exception =
                assertThrows(ConfigurationException.class, () -> loader.load(temporaryDirectory));

        assertTrue(exception.getMessage().contains(temporaryDirectory.toString()));
        assertNotNull(exception.getCause());
    }

    @Test
    void reportsMalformedPropertiesFile() throws IOException {
        Path path = temporaryDirectory.resolve("broken.properties");
        Files.writeString(path, "link.prefix=\\uZZZZ", StandardCharsets.UTF_8);

        ConfigurationException exception =
                assertThrows(ConfigurationException.class, () -> loader.load(path));

        assertTrue(exception.getMessage().contains(path.toString()));
        assertNotNull(exception.getCause());
    }

    private Properties validProperties() throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(VALID_CONFIG));
        return properties;
    }

    private Path writeProperties(Properties properties) throws IOException {
        Path path = temporaryDirectory.resolve("application.properties");
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        return path;
    }

    private void assertInvalidSetting(Properties properties, String key) throws IOException {
        Path path = writeProperties(properties);

        ConfigurationException exception =
                assertThrows(ConfigurationException.class, () -> loader.load(path));

        assertTrue(exception.getMessage().contains("'" + key + "'"));
    }
}
