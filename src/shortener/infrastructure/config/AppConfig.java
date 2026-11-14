package shortener.infrastructure.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;

public record AppConfig(
        Duration linkTtl,
        int defaultVisitLimit,
        Duration cleanupInterval,
        String shortLinkPrefix,
        int codeLength,
        Path storagePath) {

    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_PORT = 65535;

    public AppConfig {
        validateDuration("link.ttl.seconds", linkTtl);
        validateDuration("cleanup.interval.seconds", cleanupInterval);
        if (defaultVisitLimit <= 0) {
            throw invalid("link.default-limit", "значение должно быть положительным");
        }
        if (codeLength < 1 || codeLength > MAX_CODE_LENGTH) {
            throw invalid("link.code.length", "допустимо от 1 до " + MAX_CODE_LENGTH + " символов");
        }
        validatePrefix(shortLinkPrefix);
        if (storagePath == null || storagePath.toString().isBlank()) {
            throw invalid("storage.path", "путь к файлу не должен быть пустым");
        }
    }

    private static void validateDuration(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(key, "длительность должна быть положительной");
        }
        try {
            value.toMillis();
        } catch (ArithmeticException exception) {
            throw invalid(key, "длительность слишком велика для представления в миллисекундах");
        }
    }

    private static void validatePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw invalid("link.prefix", "префикс не должен быть пустым");
        }
        URI uri;
        try {
            uri = new URI(prefix);
        } catch (URISyntaxException exception) {
            throw invalid("link.prefix", "некорректный синтаксис URI");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw invalid("link.prefix", "нужен абсолютный URI http или https с адресом узла");
        }
        if (uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalid(
                    "link.prefix",
                    "префикс не должен содержать учётные данные, запрос или фрагмент");
        }
        if (uri.getPort() == 0 || uri.getPort() > MAX_PORT) {
            throw invalid("link.prefix", "порт должен быть в диапазоне от 1 до " + MAX_PORT);
        }
        if (!uri.getRawPath().endsWith("/")) {
            throw invalid("link.prefix", "префикс должен оканчиваться символом /");
        }
    }

    private static ConfigurationException invalid(String key, String reason) {
        return new ConfigurationException("Параметр '" + key + "': " + reason + ".");
    }
}
