package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.scheduling.PeriodicCleanup;
import shortener.infrastructure.storage.JsonLinkRepository;
import shortener.support.MutableClock;

class FileStorageTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final String PREFIX = "https://short.local/";

    @TempDir Path directory;

    @Test
    void restoresTwoUsersEditedLinksCountersAndNotifications() {
        AppConfig config = new ConfigLoader().load(Path.of("config/application.properties"));
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        MutableClock clock = new MutableClock(CREATED_AT);
        UserSession session = new UserSession();
        ShortLinkService users =
                new ShortLinkService(
                        repository,
                        session,
                        new RandomCodeGenerator(config.codeLength(), new Random(42)),
                        clock,
                        config.defaultVisitLimit(),
                        config.linkTtl());
        LinkExpirationService expiration =
                new LinkExpirationService(repository, clock, config.shortLinkPrefix());
        LinkManagementService management =
                new LinkManagementService(
                        repository,
                        session,
                        expiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        List<URI> openedUris = new ArrayList<>();
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        openedUris::add,
                        expiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        ShortLink first = users.create("https://example.org/Original", 1);
        users.startNewUser();
        ShortLink second = users.create("https://example.org/Original", 3);
        users.selectUser(first.ownerId().toString());
        String shortLink = config.shortLinkPrefix() + first.code();
        management.changeVisitLimit(shortLink, 2);
        management.changeOriginalUrl(shortLink, "https://example.org/New?Token=AbC");
        opening.open(shortLink);
        ShortLink saved = opening.open(shortLink);

        JsonLinkRepository restored = new JsonLinkRepository(path);
        LinkExpirationService restoredExpiration =
                new LinkExpirationService(restored, clock, config.shortLinkPrefix());
        UserSession restoredSession = new UserSession();
        ShortLinkService restoredUsers =
                new ShortLinkService(
                        restored,
                        restoredSession,
                        () -> "Unused01",
                        clock,
                        config.defaultVisitLimit(),
                        config.linkTtl());
        LinkManagementService restoredManagement =
                new LinkManagementService(
                        restored,
                        restoredSession,
                        restoredExpiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        assertTrue(restoredUsers.currentUserId().isEmpty());
        restoredUsers.selectUser(first.ownerId().toString());

        assertEquals(saved, restoredManagement.getOwnLink(shortLink));
        assertEquals(List.of(saved), restoredManagement.listOwnLinks());
        assertEquals(1, restoredUsers.readNotifications().size());
        assertTrue(restoredUsers.readNotifications().isEmpty());
        restoredUsers.selectUser(second.ownerId().toString());
        assertEquals(List.of(second), restoredManagement.listOwnLinks());
        assertThrows(
                IllegalStateException.class, () -> restoredManagement.deleteOwnLink(shortLink));
        assertEquals(List.of(saved.originalUri(), saved.originalUri()), openedUris);
    }

    @Test
    void cleansPersistedExpiredRecordsOnStartupAndKeepsOwnerNotification() {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink link = link();
        repository.add(link);
        MutableClock clock = new MutableClock(link.expiresAt());
        JsonLinkRepository loaded = new JsonLinkRepository(path);
        LinkExpirationService expiration = new LinkExpirationService(loaded, clock, PREFIX);
        List<RuntimeException> failures = new ArrayList<>();

        try (PeriodicCleanup cleanup =
                new PeriodicCleanup(
                        expiration::cleanup,
                        Duration.ofDays(1),
                        Executors.newSingleThreadScheduledExecutor(),
                        failures::add)) {
            cleanup.start();
        }

        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertTrue(restored.findAll().isEmpty());
        assertTrue(restored.userExists(link.ownerId()));
        List<Notification> pending = restored.drainNotifications(link.ownerId());
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().message().contains("истёк"));
        assertTrue(new JsonLinkRepository(path).drainNotifications(link.ownerId()).isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test
    void persistsOpeningAndConcurrentCleanupWithoutPartialState() throws Exception {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink link = link();
        repository.add(link);
        MutableClock clock = new MutableClock(CREATED_AT);
        LinkExpirationService expiration = new LinkExpirationService(repository, clock, PREFIX);
        CountDownLatch browserEntered = new CountDownLatch(1);
        CountDownLatch releaseBrowser = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        uri -> {
                            browserEntered.countDown();
                            await(releaseBrowser);
                        },
                        expiration,
                        PREFIX,
                        link.code().length());

        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var opened = workers.submit(() -> opening.open(PREFIX + link.code()));
                assertTrue(browserEntered.await(5, TimeUnit.SECONDS));
                clock.set(link.expiresAt());
                var cleaned =
                        workers.submit(
                                () -> {
                                    cleanupStarted.countDown();
                                    return expiration.cleanup();
                                });
                assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
                releaseBrowser.countDown();

                assertEquals(1, opened.get(5, TimeUnit.SECONDS).usedCount());
                assertEquals(1, cleaned.get(5, TimeUnit.SECONDS));
            } finally {
                releaseBrowser.countDown();
                workers.shutdownNow();
            }
        }

        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertTrue(restored.findAll().isEmpty());
        assertTrue(restored.userExists(link.ownerId()));
        List<Notification> pending = restored.drainNotifications(link.ownerId());
        assertEquals(2, pending.size());
        assertTrue(pending.getFirst().message().contains("лимит"));
        assertTrue(pending.getLast().message().contains("истёк"));
    }

    private ShortLink link() {
        return new ShortLink(
                "Abc12345",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                URI.create("https://example.org/Article"),
                1,
                0,
                CREATED_AT,
                CREATED_AT.plus(Duration.ofHours(1)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Не получен сигнал продолжения теста.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание в тесте прервано.", exception);
        }
    }
}
