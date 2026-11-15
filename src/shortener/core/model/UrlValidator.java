package shortener.core.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

public final class UrlValidator {
    private static final int MAX_PORT = 65535;

    private UrlValidator() {}

    public static URI parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Укажите исходный URL.");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Некорректный синтаксис URL.", exception);
        }
        return validate(uri);
    }

    public static URI validate(URI uri) {
        Objects.requireNonNull(uri, "Не указан исходный URI.");
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "Укажите абсолютный URL http или https с адресом узла.");
        }
        if (uri.getPort() == 0 || uri.getPort() > MAX_PORT) {
            throw new IllegalArgumentException(
                    "Порт URL должен быть в диапазоне от 1 до " + MAX_PORT + ".");
        }
        return uri;
    }
}
