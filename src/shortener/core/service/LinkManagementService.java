package shortener.core.service;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import shortener.core.model.ShortLink;
import shortener.core.model.UrlValidator;
import shortener.core.port.LinkRepository;

public final class LinkManagementService {
    private final LinkRepository repository;
    private final UserSession session;
    private final LinkExpirationService expiration;
    private final ShortLinkParser parser;

    public LinkManagementService(
            LinkRepository repository,
            UserSession session,
            LinkExpirationService expiration,
            String shortLinkPrefix,
            int codeLength) {
        this.repository = Objects.requireNonNull(repository);
        this.session = Objects.requireNonNull(session);
        this.expiration = Objects.requireNonNull(expiration);
        this.parser = new ShortLinkParser(shortLinkPrefix, codeLength);
    }

    public List<ShortLink> listOwnLinks() {
        return repository.findByOwner(session.requireUserId());
    }

    public ShortLink getOwnLink(String shortLink) {
        UUID userId = session.requireUserId();
        String code = parser.parseCode(shortLink);
        return repository.executeExclusively(() -> requireOwnedLink(code, userId, shortLink));
    }

    public ShortLink changeVisitLimit(String shortLink, int newLimit) {
        UUID userId = session.requireUserId();
        String code = parser.parseCode(shortLink);
        return repository.executeExclusively(
                () -> {
                    ShortLink link = requireOwnedLink(code, userId, shortLink);
                    expiration.requireActive(link);
                    return repository
                            .update(code, stored -> stored.withVisitLimit(newLimit))
                            .orElseThrow();
                });
    }

    public ShortLink changeOriginalUrl(String shortLink, String newUrl) {
        UUID userId = session.requireUserId();
        String code = parser.parseCode(shortLink);
        return repository.executeExclusively(
                () -> {
                    ShortLink link = requireOwnedLink(code, userId, shortLink);
                    expiration.requireActive(link);
                    URI originalUri = UrlValidator.parse(newUrl);
                    return repository
                            .update(code, stored -> stored.withOriginalUri(originalUri))
                            .orElseThrow();
                });
    }

    public void deleteOwnLink(String shortLink) {
        UUID userId = session.requireUserId();
        String code = parser.parseCode(shortLink);
        repository.executeExclusively(
                () -> {
                    ShortLink link = requireOwnedLink(code, userId, shortLink);
                    expiration.notifyIfExpired(link);
                    repository.remove(code);
                    return null;
                });
    }

    private ShortLink requireOwnedLink(String code, UUID userId, String shortLink) {
        ShortLink link =
                repository
                        .findByCode(code)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ссылка " + shortLink + " не найдена."));
        if (!link.ownerId().equals(userId)) {
            throw new IllegalStateException(
                    "Ссылка " + shortLink + " принадлежит другому пользователю.");
        }
        return link;
    }
}
