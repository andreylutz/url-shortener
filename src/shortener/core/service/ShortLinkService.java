package shortener.core.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import shortener.core.model.ShortLink;
import shortener.core.port.CodeGenerator;
import shortener.core.port.LinkRepository;

public final class ShortLinkService {
    private static final int MAX_GENERATION_ATTEMPTS = 100;
    private static final int MAX_PORT = 65535;

    private final LinkRepository repository;
    private final UserSession session;
    private final CodeGenerator codeGenerator;
    private final Clock clock;
    private final int defaultVisitLimit;
    private final Duration linkTtl;

    public ShortLinkService(
            LinkRepository repository,
            UserSession session,
            CodeGenerator codeGenerator,
            Clock clock,
            int defaultVisitLimit,
            Duration linkTtl) {
        this.repository = Objects.requireNonNull(repository);
        this.session = Objects.requireNonNull(session);
        this.codeGenerator = Objects.requireNonNull(codeGenerator);
        this.clock = Objects.requireNonNull(clock);
        this.linkTtl = Objects.requireNonNull(linkTtl);
        if (defaultVisitLimit <= 0) {
            throw new IllegalArgumentException(
                    "Лимит переходов по умолчанию должен быть положительным.");
        }
        if (linkTtl.isZero() || linkTtl.isNegative()) {
            throw new IllegalArgumentException("Срок жизни ссылки должен быть положительным.");
        }
        this.defaultVisitLimit = defaultVisitLimit;
    }

    public ShortLink create(String url) {
        return create(url, null);
    }

    public synchronized ShortLink create(String url, Integer requestedVisitLimit) {
        URI originalUri = parseOriginalUri(url);
        int visitLimit = requestedVisitLimit == null ? defaultVisitLimit : requestedVisitLimit;
        if (visitLimit <= 0) {
            throw new IllegalArgumentException("Лимит переходов должен быть положительным.");
        }
        UUID ownerId = session.currentUserId().orElseGet(this::generateUserId);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            Instant createdAt = clock.instant();
            ShortLink link =
                    new ShortLink(
                            code,
                            ownerId,
                            originalUri,
                            visitLimit,
                            0,
                            createdAt,
                            createdAt.plus(linkTtl));
            if (repository.add(link)) {
                session.select(ownerId);
                return link;
            }
        }
        throw new IllegalStateException(
                "Не удалось подобрать свободный короткий код. Повторите создание ссылки.");
    }

    public synchronized UUID selectUser(String value) {
        UUID userId = parseUserId(value);
        if (!repository.userExists(userId)) {
            throw new IllegalArgumentException("Пользователь с UUID " + userId + " не найден.");
        }
        session.select(userId);
        return userId;
    }

    public synchronized void startNewUser() {
        session.clear();
    }

    public synchronized Optional<UUID> currentUserId() {
        return session.currentUserId();
    }

    private UUID generateUserId() {
        UUID userId;
        do {
            userId = UUID.randomUUID();
        } while (repository.userExists(userId));
        return userId;
    }

    private UUID parseUserId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Укажите UUID пользователя.");
        }
        String text = value.strip();
        UUID userId;
        try {
            userId = UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неверный формат UUID пользователя.", exception);
        }
        if (!userId.toString().equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("Неверный формат UUID пользователя.");
        }
        return userId;
    }

    private URI parseOriginalUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Укажите исходный URL.");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Некорректный синтаксис URL.", exception);
        }
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
