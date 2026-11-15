package shortener.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.BrowserException;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class LinkExpirationServiceTest {
    private static final String PREFIX = "https://short.local/";
    private static final String CODE = "Abc12345";
    private static final String SHORT_LINK = PREFIX + CODE;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");

    private InMemoryLinkRepository repository;
    private MutableClock clock;
    private LinkExpirationService expiration;
    private LinkOpeningService opening;
    private AtomicInteger browserCalls;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLinkRepository();
        clock = new MutableClock(CREATED_AT);
        expiration = new LinkExpirationService(repository, clock, PREFIX);
        browserCalls = new AtomicInteger();
        opening =
                new LinkOpeningService(
                        repository,
                        uri -> browserCalls.incrementAndGet(),
                        expiration,
                        PREFIX,
                        CODE.length());
    }

    @ParameterizedTest
    @ValueSource(longs = {3600, 86400})
    void blocksAtExactExpirationBoundaryWithoutWaitingForCleanup(long ttlSeconds) {
        ShortLink link = storeLink(Duration.ofSeconds(ttlSeconds), 3, 0);
        clock.set(link.expiresAt().minusNanos(1));
        assertEquals(1, opening.open(SHORT_LINK).usedCount());

        clock.set(link.expiresAt());
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));

        assertTrue(exception.getMessage().contains("срок действия истёк"));
        assertTrue(exception.getMessage().contains(SHORT_LINK));
        assertEquals(1, browserCalls.get());
        assertEquals(1, repository.findByCode(CODE).orElseThrow().usedCount());
        assertTrue(repository.findByCode(CODE).orElseThrow().expirationNotified());
    }

    @Test
    void notifiesAboutExpirationOnceEvenAfterNotificationWasRead() {
        ShortLink link = storeLink(Duration.ofHours(1), 3, 0);
        clock.set(link.expiresAt());
        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));
        List<Notification> first = repository.drainNotifications(OWNER);

        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));
        assertEquals(1, expiration.cleanup());
        assertEquals(0, expiration.cleanup());

        assertEquals(1, first.size());
        assertEquals(SHORT_LINK, first.getFirst().shortLink());
        assertTrue(first.getFirst().message().contains("истёк"));
        assertTrue(repository.drainNotifications(OWNER).isEmpty());
        assertTrue(repository.findByCode(CODE).isEmpty());
    }

    @Test
    void removesExpiredLinksAndPreservesNotificationAndOwner() {
        storeLink(Duration.ofHours(1), 3, 0);
        ShortLink active =
                new ShortLink(
                        "Def67890",
                        OWNER,
                        URI.create("https://example.org/Active"),
                        3,
                        0,
                        CREATED_AT,
                        CREATED_AT.plus(Duration.ofDays(1)));
        repository.add(active);
        clock.set(CREATED_AT.plus(Duration.ofHours(1)));

        assertEquals(1, expiration.cleanup());

        assertTrue(repository.findByCode(CODE).isEmpty());
        assertEquals(List.of(active), repository.findByOwner(OWNER));
        assertTrue(repository.userExists(OWNER));
        List<Notification> pending = repository.drainNotifications(OWNER);
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().message().contains(SHORT_LINK));
        assertTrue(pending.getFirst().message().contains("Создайте новую ссылку"));
        assertThrows(IllegalArgumentException.class, () -> opening.open(SHORT_LINK));
        assertEquals(0, browserCalls.get());
    }

    @Test
    void doesNotRestoreExpiredLinkWhenClockMovesBackwards() {
        ShortLink link = storeLink(Duration.ofHours(1), 3, 0);
        clock.set(link.expiresAt());
        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));

        clock.set(CREATED_AT);

        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));
        assertEquals(1, expiration.cleanup());
        assertEquals(1, repository.drainNotifications(OWNER).size());
        assertEquals(0, browserCalls.get());
    }

    @Test
    void reportsExpirationBeforeExhaustedLimit() {
        ShortLink link = storeLink(Duration.ofHours(1), 1, 1);
        clock.set(link.expiresAt());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));

        assertTrue(exception.getMessage().contains("срок действия истёк"));
        assertEquals(0, browserCalls.get());
        assertEquals(1, repository.drainNotifications(OWNER).size());
    }

    @Test
    void notifiesWhenLastAllowedOpeningSucceedsWithoutRepeatingOnBlockedAttempts() {
        storeLink(Duration.ofDays(1), 2, 0);
        opening.open(SHORT_LINK);
        assertTrue(repository.drainNotifications(OWNER).isEmpty());

        opening.open(SHORT_LINK);
        List<Notification> pending = repository.drainNotifications(OWNER);
        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));
        assertThrows(IllegalStateException.class, () -> opening.open(SHORT_LINK));

        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().message().contains("достигнут лимит 2"));
        assertEquals(SHORT_LINK, pending.getFirst().shortLink());
        assertTrue(repository.drainNotifications(OWNER).isEmpty());
    }

    @Test
    void notifiesAgainWhenIncreasedLimitIsReached() {
        storeLink(Duration.ofDays(1), 1, 0);
        opening.open(SHORT_LINK);
        assertEquals(1, repository.drainNotifications(OWNER).size());
        repository.update(
                CODE,
                link ->
                        new ShortLink(
                                link.code(),
                                link.ownerId(),
                                link.originalUri(),
                                2,
                                link.usedCount(),
                                link.createdAt(),
                                link.expiresAt(),
                                link.expirationNotified()));

        opening.open(SHORT_LINK);

        List<Notification> pending = repository.drainNotifications(OWNER);
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().message().contains("достигнут лимит 2"));
    }

    @Test
    void doesNotNotifyAboutLimitWhenBrowserFails() {
        storeLink(Duration.ofDays(1), 1, 0);
        LinkOpeningService failing =
                new LinkOpeningService(
                        repository,
                        uri -> {
                            throw new BrowserException("Браузер недоступен.");
                        },
                        expiration,
                        PREFIX,
                        CODE.length());

        assertThrows(IllegalStateException.class, () -> failing.open(SHORT_LINK));

        assertTrue(repository.drainNotifications(OWNER).isEmpty());
        assertEquals(0, repository.findByCode(CODE).orElseThrow().usedCount());
    }

    @Test
    void countsOpeningThatStartedBeforeExpirationAndThenCleansUp() {
        ShortLink link = storeLink(Duration.ofHours(1), 1, 0);
        LinkOpeningService slowBrowser =
                new LinkOpeningService(
                        repository,
                        uri -> clock.set(link.expiresAt()),
                        expiration,
                        PREFIX,
                        CODE.length());

        assertEquals(1, slowBrowser.open(SHORT_LINK).usedCount());
        assertEquals(1, expiration.cleanup());

        assertEquals(2, repository.drainNotifications(OWNER).size());
    }

    @Test
    void doesNotRemoveActiveLinks() {
        ShortLink link = storeLink(Duration.ofDays(1), 3, 0);

        assertEquals(0, expiration.cleanup());

        assertEquals(link, repository.findByCode(CODE).orElseThrow());
        assertFalse(link.expirationNotified());
        assertTrue(repository.drainNotifications(OWNER).isEmpty());
    }

    private ShortLink storeLink(Duration ttl, int limit, int used) {
        ShortLink link =
                new ShortLink(
                        CODE,
                        OWNER,
                        URI.create("https://example.org/Article"),
                        limit,
                        used,
                        CREATED_AT,
                        CREATED_AT.plus(ttl));
        repository.add(link);
        return link;
    }
}
