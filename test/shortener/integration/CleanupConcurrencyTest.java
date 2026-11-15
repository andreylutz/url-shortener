package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkOpeningService;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class CleanupConcurrencyTest {
    private static final String PREFIX = "https://short.local/";
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();
    private final MutableClock clock = new MutableClock(CREATED_AT);
    private final LinkExpirationService expiration =
            new LinkExpirationService(repository, clock, PREFIX);

    @Test
    void cleanupWaitsForOpeningAndDoesNotLoseCounterOrNotifications() throws Exception {
        ShortLink link = storeLink();
        CountDownLatch browserEntered = new CountDownLatch(1);
        CountDownLatch releaseBrowser = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        AtomicInteger browserCalls = new AtomicInteger();
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        uri -> {
                            browserCalls.incrementAndGet();
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
                assertTrue(repository.findByCode(link.code()).isEmpty());
                assertEquals(1, browserCalls.get());
                List<Notification> pending = repository.drainNotifications(OWNER);
                assertEquals(2, pending.size());
                assertTrue(pending.getFirst().message().contains("лимит"));
                assertTrue(pending.getLast().message().contains("истёк"));
            } finally {
                releaseBrowser.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void checksExpirationAfterWaitingForExclusiveAccess() throws Exception {
        ShortLink link = storeLink();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch openingStarted = new CountDownLatch(1);
        AtomicInteger browserCalls = new AtomicInteger();
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        uri -> browserCalls.incrementAndGet(),
                        expiration,
                        PREFIX,
                        link.code().length());

        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var holder =
                        workers.submit(
                                () ->
                                        repository.executeExclusively(
                                                () -> {
                                                    lockHeld.countDown();
                                                    await(releaseLock);
                                                    return null;
                                                }));
                assertTrue(lockHeld.await(5, TimeUnit.SECONDS));
                var attempt =
                        workers.submit(
                                () -> {
                                    openingStarted.countDown();
                                    return opening.open(PREFIX + link.code());
                                });
                assertTrue(openingStarted.await(5, TimeUnit.SECONDS));
                clock.set(link.expiresAt());

                releaseLock.countDown();
                holder.get(5, TimeUnit.SECONDS);
                ExecutionException failure =
                        assertThrows(
                                ExecutionException.class, () -> attempt.get(5, TimeUnit.SECONDS));

                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertTrue(failure.getCause().getMessage().contains("срок действия истёк"));
                assertEquals(0, browserCalls.get());
                assertEquals(0, repository.findByCode(link.code()).orElseThrow().usedCount());
                assertEquals(1, repository.drainNotifications(OWNER).size());
            } finally {
                releaseLock.countDown();
                workers.shutdownNow();
            }
        }
    }

    private ShortLink storeLink() {
        ShortLink link =
                new ShortLink(
                        "Abc12345",
                        OWNER,
                        URI.create("https://example.org"),
                        1,
                        0,
                        CREATED_AT,
                        CREATED_AT.plus(Duration.ofHours(1)));
        repository.add(link);
        return link;
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
