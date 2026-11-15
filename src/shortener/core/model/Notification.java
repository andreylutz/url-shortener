package shortener.core.model;

import java.util.Objects;
import java.util.UUID;

public record Notification(UUID ownerId, String shortLink, String message) {
    public Notification {
        Objects.requireNonNull(ownerId, "Не указан получатель уведомления.");
        if (shortLink == null || shortLink.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Уведомление должно содержать ссылку и причину.");
        }
    }

    public static Notification expired(ShortLink link, String prefix) {
        String shortLink = prefix + link.code();
        return new Notification(
                link.ownerId(),
                shortLink,
                "Срок действия ссылки "
                        + shortLink
                        + " истёк. Переходы заблокированы. Создайте новую ссылку.");
    }

    public static Notification limitReached(ShortLink link, String prefix) {
        String shortLink = prefix + link.code();
        return new Notification(
                link.ownerId(),
                shortLink,
                "Для ссылки "
                        + shortLink
                        + " достигнут лимит "
                        + link.visitLimit()
                        + " переходов. Увеличьте лимит до истечения срока действия или создайте новую ссылку.");
    }
}
