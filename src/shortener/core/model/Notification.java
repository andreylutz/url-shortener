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

    public static Notification expired(ShortLink link, String shortLinkPrefix) {
        String shortLink = shortLinkPrefix + link.code();
        String message =
                "Срок действия ссылки %s истёк. Переходы заблокированы. Создайте новую ссылку."
                        .formatted(shortLink);
        return new Notification(link.ownerId(), shortLink, message);
    }

    public static Notification limitReached(ShortLink link, String shortLinkPrefix) {
        String shortLink = shortLinkPrefix + link.code();
        String message =
                ("Для ссылки %s достигнут лимит %s переходов. "
                                + "Увеличьте лимит до истечения срока действия или создайте новую ссылку.")
                        .formatted(shortLink, link.visitLimit());
        return new Notification(link.ownerId(), shortLink, message);
    }
}
