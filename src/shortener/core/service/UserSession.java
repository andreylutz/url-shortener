package shortener.core.service;

import java.util.Optional;
import java.util.UUID;

public final class UserSession {
    private UUID currentUserId;

    Optional<UUID> currentUserId() {
        return Optional.ofNullable(currentUserId);
    }

    UUID requireUserId() {
        return currentUserId()
                .orElseThrow(
                        () -> new IllegalStateException("Сначала выберите пользователя по UUID."));
    }

    void select(UUID userId) {
        currentUserId = userId;
    }

    void clear() {
        currentUserId = null;
    }
}
