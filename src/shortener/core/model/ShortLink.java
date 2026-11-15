package shortener.core.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShortLink(
        String code,
        UUID ownerId,
        URI originalUri,
        int visitLimit,
        int usedCount,
        Instant createdAt,
        Instant expiresAt) {

    public ShortLink {
        if (code == null || !code.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Короткий код должен содержать только буквы и цифры.");
        }
        Objects.requireNonNull(ownerId, "Не указан владелец ссылки.");
        Objects.requireNonNull(originalUri, "Не указан исходный URI.");
        Objects.requireNonNull(createdAt, "Не указано время создания ссылки.");
        Objects.requireNonNull(expiresAt, "Не указано время истечения ссылки.");
        if (visitLimit <= 0) {
            throw new IllegalArgumentException("Лимит переходов должен быть положительным.");
        }
        if (usedCount < 0 || usedCount > visitLimit) {
            throw new IllegalArgumentException("Счётчик переходов должен быть от 0 до лимита.");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException(
                    "Время истечения должно быть позже времени создания.");
        }
    }

    public ShortLink recordSuccessfulOpen() {
        if (usedCount >= visitLimit) {
            throw new IllegalStateException("Лимит переходов исчерпан.");
        }
        return new ShortLink(
                code, ownerId, originalUri, visitLimit, usedCount + 1, createdAt, expiresAt);
    }
}
