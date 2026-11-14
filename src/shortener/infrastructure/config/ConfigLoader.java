package shortener.infrastructure.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class ConfigLoader {
    public AppConfig load(Path path) {
        Properties properties = readProperties(path);
        return new AppConfig(
                readDuration(properties, "link.ttl.seconds"),
                readInteger(properties, "link.default-limit"),
                readDuration(properties, "cleanup.interval.seconds"),
                readRequired(properties, "link.prefix"),
                readInteger(properties, "link.code.length"),
                readPath(properties, "storage.path"));
    }

    private Properties readProperties(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new ConfigurationException(
                    "Не удалось прочитать файл конфигурации '"
                            + path
                            + "'. Проверьте путь и доступ к файлу.",
                    exception);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "Некорректный формат .properties в файле '" + path + "'.", exception);
        }
    }

    private Duration readDuration(Properties properties, String key) {
        String value = readRequired(properties, key);
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(
                    "Параметр '" + key + "': ожидается целое число секунд в диапазоне long.",
                    exception);
        }
    }

    private int readInteger(Properties properties, String key) {
        String value = readRequired(properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(
                    "Параметр '" + key + "': ожидается целое число в диапазоне int.", exception);
        }
    }

    private Path readPath(Properties properties, String key) {
        String value = readRequired(properties, key);
        try {
            Path path = Path.of(value);
            if (Files.isDirectory(path)) {
                throw new ConfigurationException(
                        "Параметр '" + key + "': указан каталог вместо файла.");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new ConfigurationException(
                    "Параметр '" + key + "': некорректный путь к файлу.", exception);
        }
    }

    private String readRequired(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("Параметр '" + key + "': значение обязательно.");
        }
        return value.strip();
    }
}
