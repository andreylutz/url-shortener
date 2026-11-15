package shortener.core.service;

import java.util.Objects;
import shortener.core.model.ShortLink;
import shortener.core.port.BrowserException;
import shortener.core.port.BrowserOpener;
import shortener.core.port.LinkRepository;

public final class LinkOpeningService {
    private final LinkRepository repository;
    private final BrowserOpener browser;
    private final String shortLinkPrefix;
    private final int codeLength;

    public LinkOpeningService(
            LinkRepository repository,
            BrowserOpener browser,
            String shortLinkPrefix,
            int codeLength) {
        this.repository = Objects.requireNonNull(repository);
        this.browser = Objects.requireNonNull(browser);
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
        return repository
                .update(code, link -> openAndCount(link, shortLink))
                .orElseThrow(
                        () -> new IllegalArgumentException("Ссылка " + shortLink + " не найдена."));
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
        return link.recordSuccessfulOpen();
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
