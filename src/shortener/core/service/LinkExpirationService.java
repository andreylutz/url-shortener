package shortener.core.service;

import java.time.Clock;
import java.util.Objects;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.LinkRepository;

public final class LinkExpirationService {
    private final LinkRepository repository;
    private final Clock clock;
    private final String shortLinkPrefix;

    public LinkExpirationService(LinkRepository repository, Clock clock, String shortLinkPrefix) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        if (shortLinkPrefix == null || shortLinkPrefix.isBlank()) {
            throw new IllegalArgumentException("Префикс коротких ссылок не должен быть пустым.");
        }
        this.shortLinkPrefix = shortLinkPrefix;
    }

    public int cleanup() {
        return repository.executeExclusively(
                () -> {
                    int removed = 0;
                    for (ShortLink link : repository.findAll()) {
                        if (notifyIfExpired(link)) {
                            repository.remove(link.code());
                            removed++;
                        }
                    }
                    return removed;
                });
    }

    /** Вызывается внутри исключительного доступа к хранилищу после чтения актуальной записи. */
    void requireActive(ShortLink link) {
        if (notifyIfExpired(link)) {
            throw new IllegalStateException(
                    "Переход по ссылке "
                            + shortLinkPrefix
                            + link.code()
                            + " заблокирован: срок действия истёк. Создайте новую ссылку.");
        }
    }

    /** Вызывается под общей блокировкой, в том числе при удалении ссылки владельцем. */
    boolean notifyIfExpired(ShortLink link) {
        if (!link.isExpiredAt(clock.instant())) {
            return false;
        }
        if (!link.expirationNotified()) {
            repository.addNotification(Notification.expired(link, shortLinkPrefix));
            repository.update(link.code(), ShortLink::markExpirationNotified);
        }
        return true;
    }
}
