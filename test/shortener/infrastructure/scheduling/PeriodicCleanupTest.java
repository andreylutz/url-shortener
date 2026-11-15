package shortener.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkExpirationService;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class PeriodicCleanupTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();
    private final MutableClock clock = new MutableClock(CREATED_AT);
    private final LinkExpirationService expiration =
            new LinkExpirationService(repository, clock, "https://short.local/");

    @Test
    void cleansAtStartupSchedulesConfiguredIntervalAndStopsExecutor() {
        ShortLink link = storeLink();
        clock.set(link.expiresAt());
        CapturingExecutor executor = new CapturingExecutor();
        List<RuntimeException> failures = new ArrayList<>();

        try (PeriodicCleanup cleanup =
                new PeriodicCleanup(
                        expiration::cleanup, Duration.ofSeconds(10), executor, failures::add)) {
            cleanup.start();

            assertTrue(repository.findByCode(link.code()).isEmpty());
            assertEquals(1, repository.drainNotifications(OWNER).size());
            assertEquals(10000, executor.initialDelay);
            assertEquals(10000, executor.period);
            assertEquals(TimeUnit.MILLISECONDS, executor.unit);
            assertFalse(executor.future.isCancelled());
        }

        assertTrue(failures.isEmpty());
        assertTrue(executor.future.isCancelled());
        assertTrue(executor.isTerminated());
    }

    @Test
    void removesExpiredLinkOnRealScheduledExecutorWithoutUserCommands() throws Exception {
        ShortLink link = storeLink();
        CountDownLatch removed = new CountDownLatch(1);
        ConcurrentLinkedQueue<RuntimeException> failures = new ConcurrentLinkedQueue<>();
        var executor = Executors.newSingleThreadScheduledExecutor();
        Runnable task =
                () -> {
                    if (expiration.cleanup() > 0) {
                        removed.countDown();
                    }
                };

        try (PeriodicCleanup cleanup =
                new PeriodicCleanup(task, Duration.ofMillis(1), executor, failures::add)) {
            cleanup.start();
            assertTrue(repository.findByCode(link.code()).isPresent());

            clock.set(link.expiresAt());

            assertTrue(removed.await(5, TimeUnit.SECONDS));
            assertTrue(repository.findByCode(link.code()).isEmpty());
            assertEquals(1, repository.drainNotifications(OWNER).size());
        }

        assertTrue(failures.isEmpty());
        assertTrue(executor.isTerminated());
    }

    @Test
    void reportsScheduledFailureAndAllowsFollowingCleanup() {
        ShortLink link = storeLink();
        CapturingExecutor executor = new CapturingExecutor();
        List<RuntimeException> failures = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = new IllegalStateException("Ошибка очистки.");
        Runnable task =
                () -> {
                    if (attempts.incrementAndGet() == 2) {
                        throw failure;
                    }
                    expiration.cleanup();
                };

        try (PeriodicCleanup cleanup =
                new PeriodicCleanup(task, Duration.ofSeconds(10), executor, failures::add)) {
            cleanup.start();
            clock.set(link.expiresAt());

            executor.command.run();
            assertEquals(List.of(failure), failures);
            assertTrue(repository.findByCode(link.code()).isPresent());
            executor.command.run();

            assertTrue(repository.findByCode(link.code()).isEmpty());
            assertFalse(executor.future.isCancelled());
            assertEquals(3, attempts.get());
        }
    }

    @Test
    void propagatesStartupFailureWithoutSchedulingTask() {
        CapturingExecutor executor = new CapturingExecutor();
        RuntimeException failure = new IllegalStateException("Ошибка начальной очистки.");
        List<RuntimeException> failures = new ArrayList<>();

        try (PeriodicCleanup cleanup =
                new PeriodicCleanup(
                        () -> {
                            throw failure;
                        },
                        Duration.ofSeconds(10),
                        executor,
                        failures::add)) {
            assertSame(failure, assertThrows(IllegalStateException.class, cleanup::start));
            assertNull(executor.command);
        }

        assertTrue(failures.isEmpty());
        assertTrue(executor.isTerminated());
    }

    private ShortLink storeLink() {
        ShortLink link =
                new ShortLink(
                        "Abc12345",
                        OWNER,
                        URI.create("https://example.org"),
                        3,
                        0,
                        CREATED_AT,
                        CREATED_AT.plus(Duration.ofDays(1)));
        repository.add(link);
        return link;
    }

    private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {
        private Runnable command;
        private long initialDelay;
        private long period;
        private TimeUnit unit;
        private ScheduledFuture<?> future;

        private CapturingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            this.command = command;
            this.initialDelay = initialDelay;
            this.period = period;
            this.unit = unit;
            // Реальный таймер отложен: тест запускает захваченную задачу вручную.
            future = super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
            return future;
        }
    }
}
