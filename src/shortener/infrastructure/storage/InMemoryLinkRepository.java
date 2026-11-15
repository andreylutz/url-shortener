package shortener.infrastructure.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import shortener.core.model.ShortLink;
import shortener.core.port.LinkRepository;

public final class InMemoryLinkRepository implements LinkRepository {
    private final Map<String, ShortLink> links = new LinkedHashMap<>();
    private final Set<UUID> users = new HashSet<>();

    @Override
    public synchronized boolean add(ShortLink link) {
        if (links.containsKey(link.code())) {
            return false;
        }
        links.put(link.code(), link);
        users.add(link.ownerId());
        return true;
    }

    @Override
    public synchronized Optional<ShortLink> findByCode(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public synchronized Optional<ShortLink> update(
            String code, UnaryOperator<ShortLink> operation) {
        ShortLink existing = links.get(code);
        if (existing == null) {
            return Optional.empty();
        }
        ShortLink updated = Objects.requireNonNull(operation.apply(existing));
        if (!existing.code().equals(updated.code())
                || !existing.ownerId().equals(updated.ownerId())) {
            throw new IllegalArgumentException(
                    "Обновление не должно менять код или владельца ссылки.");
        }
        links.put(code, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized List<ShortLink> findByOwner(UUID ownerId) {
        List<ShortLink> result = new ArrayList<>();
        for (ShortLink link : links.values()) {
            if (link.ownerId().equals(ownerId)) {
                result.add(link);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean userExists(UUID userId) {
        return users.contains(userId);
    }
}
