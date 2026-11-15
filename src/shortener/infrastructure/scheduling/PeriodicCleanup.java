package shortener.infrastructure.scheduling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class PeriodicCleanup implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Runnable cleanup;
    private final long intervalMillis;
    private final ScheduledExecutorService executor;
    private final Consumer<RuntimeException> onFailure;
    private ScheduledFuture<?> task;
    private boolean closed;

    /** Исполнитель принадлежит очистке и останавливается при её закрытии. */
    public PeriodicCleanup(
            Runnable cleanup,
            Duration interval,
            ScheduledExecutorService executor,
            Consumer<RuntimeException> onFailure) {
        this.cleanup = Objects.requireNonNull(cleanup);
        this.intervalMillis = Objects.requireNonNull(interval).toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "Интервал очистки должен быть не меньше миллисекунды.");
        }
        this.executor = Objects.requireNonNull(executor);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    public synchronized void start() {
        if (closed || task != null) {
            throw new IllegalStateException("Очистка уже запущена или закрыта.");
        }
        cleanup.run();
        task =
                executor.scheduleAtFixedRate(
                        this::runSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void runSafely() {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            onFailure.accept(exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (task != null) {
            task.cancel(false);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Не удалось остановить исполнитель фоновой очистки.");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Остановка фоновой очистки прервана.", exception);
        }
    }
}
