package shortener.infrastructure.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.LinkRepository;
import shortener.core.port.StorageException;

public final class JsonLinkRepository implements LinkRepository {
    private final JsonStorageFile file;
    private InMemoryLinkRepository memory;
    private boolean insideOperation;
    private StorageException failure;

    public JsonLinkRepository(Path path) {
        file = new JsonStorageFile(path);
        memory = InMemoryLinkRepository.fromSnapshot(file.load());
    }

    @Override
    public synchronized <T> T executeExclusively(Supplier<T> operation) {
        requireAvailable();
        Objects.requireNonNull(operation);
        if (insideOperation) {
            return operation.get();
        }
        StorageSnapshot before = memory.snapshot();
        T result = null;
        RuntimeException rejection = null;
        insideOperation = true;
        try {
            result = operation.get();
        } catch (RuntimeException exception) {
            // Отказ по TTL не отменяет уже созданное уведомление владельцу.
            rejection = exception;
        } finally {
            insideOperation = false;
        }
        try {
            StorageSnapshot after = memory.snapshot();
            if (!after.equals(before)) {
                file.save(after);
            }
        } catch (StorageException exception) {
            memory = InMemoryLinkRepository.fromSnapshot(before);
            failure = exception;
            if (rejection != null) {
                exception.addSuppressed(rejection);
            }
            throw exception;
        }
        if (rejection != null) {
            throw rejection;
        }
        return result;
    }

    @Override
    public synchronized boolean add(ShortLink link) {
        return executeExclusively(() -> memory.add(link));
    }

    @Override
    public synchronized Optional<ShortLink> findByCode(String code) {
        requireAvailable();
        return memory.findByCode(code);
    }

    @Override
    public synchronized Optional<ShortLink> update(
            String code, UnaryOperator<ShortLink> operation) {
        return executeExclusively(() -> memory.update(code, operation));
    }

    @Override
    public synchronized List<ShortLink> findByOwner(UUID ownerId) {
        requireAvailable();
        return memory.findByOwner(ownerId);
    }

    @Override
    public synchronized List<ShortLink> findAll() {
        requireAvailable();
        return memory.findAll();
    }

    @Override
    public synchronized boolean remove(String code) {
        return executeExclusively(() -> memory.remove(code));
    }

    @Override
    public synchronized boolean userExists(UUID userId) {
        requireAvailable();
        return memory.userExists(userId);
    }

    @Override
    public synchronized void addNotification(Notification notification) {
        executeExclusively(
                () -> {
                    memory.addNotification(notification);
                    return null;
                });
    }

    @Override
    public synchronized List<Notification> drainNotifications(UUID ownerId) {
        return executeExclusively(() -> memory.drainNotifications(ownerId));
    }

    private void requireAvailable() {
        if (failure != null) {
            throw new StorageException(
                    "Хранилище недоступно после ошибки записи. Проверьте файл и перезапустите приложение.",
                    failure);
        }
    }
}
