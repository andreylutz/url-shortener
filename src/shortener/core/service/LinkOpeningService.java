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
    private final int codeLength;

    public LinkOpeningService(
            LinkRepository repository,
            BrowserOpener browser,
            LinkExpirationService expiration,
            String shortLinkPrefix,
            int codeLength) {
        this.repository = Objects.requireNonNull(repository);
        this.browser = Objects.requireNonNull(browser);
        this.expiration = Objects.requireNonNull(expiration);
        if (shortLinkPrefix == null || shortLinkPrefix.isBlank()) {
            throw new IllegalArgumentException("Префикс коротких ссылок не должен быть пустым.");
        }
        if (codeLength <= 0) {
            throw new IllegalArgumentException("Длина короткого кода должна быть положительной.");
        }
        this.shortLinkPrefix = shortLinkPrefix;
        this.codeLength = codeLength;
    }

    public ShortLink open(String shortLink) {
        String code = parseCode(shortLink);
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

    private String parseCode(String shortLink) {
        if (shortLink == null || shortLink.isBlank()) {
            throw new IllegalArgumentException("Укажите короткую ссылку.");
        }
        if (!shortLink.startsWith(shortLinkPrefix)) {
            throw new IllegalArgumentException(
                    "Некорректная короткая ссылка "
                            + shortLink
                            + ": ожидается префикс "
                            + shortLinkPrefix);
        }
        String code = shortLink.substring(shortLinkPrefix.length());
        if (code.length() != codeLength || !code.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Некорректная короткая ссылка "
                            + shortLink
                            + ": код должен содержать "
                            + codeLength
                            + " латинских букв или цифр.");
        }
        return code;
    }
}
