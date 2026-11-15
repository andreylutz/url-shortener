package shortener.core.service;

import java.util.Objects;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.BrowserException;
import shortener.core.port.BrowserOpener;
import shortener.core.port.LinkRepository;

public final class LinkOpeningService {
    private final LinkRepository repository;
    private final BrowserOpener browser;
    private final LinkExpirationService expiration;
    private final String shortLinkPrefix;
    private final ShortLinkParser parser;

    public LinkOpeningService(
            LinkRepository repository,
            BrowserOpener browser,
            LinkExpirationService expiration,
            String shortLinkPrefix,
            int codeLength) {
        this.repository = Objects.requireNonNull(repository);
        this.browser = Objects.requireNonNull(browser);
        this.expiration = Objects.requireNonNull(expiration);
        this.parser = new ShortLinkParser(shortLinkPrefix, codeLength);
        this.shortLinkPrefix = shortLinkPrefix;
    }

    public ShortLink open(String shortLink) {
        String code = parser.parseCode(shortLink);
        return repository.executeExclusively(
                () -> {
                    ShortLink link =
                            repository
                                    .findByCode(code)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Ссылка "
                                                                    + shortLink
                                                                    + " не найдена."));
                    expiration.requireActive(link);
                    return openAndCount(link, shortLink);
                });
    }

    private ShortLink openAndCount(ShortLink link, String shortLink) {
        if (link.usedCount() >= link.visitLimit()) {
            throw new IllegalStateException(
                    "Переход по ссылке "
                            + shortLink
                            + " заблокирован: лимит переходов исчерпан. "
                            + "Создайте новую ссылку или увеличьте лимит, если вы её владелец.");
        }
        try {
            browser.open(link.originalUri());
        } catch (BrowserException exception) {
            throw new IllegalStateException(
                    "Не удалось открыть ссылку "
                            + shortLink
                            + ": "
                            + exception.getMessage()
                            + " Переход не израсходован.",
                    exception);
        }
        ShortLink opened =
                repository.update(link.code(), ShortLink::recordSuccessfulOpen).orElseThrow();
        if (opened.usedCount() == opened.visitLimit()) {
            repository.addNotification(Notification.limitReached(opened, shortLinkPrefix));
        }
        return opened;
    }
}
