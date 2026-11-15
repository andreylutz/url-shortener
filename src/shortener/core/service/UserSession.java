package shortener.core.service;

import java.util.Optional;
import java.util.UUID;

public final class UserSession {
    private UUID currentUserId;

    Optional<UUID> currentUserId() {
        return Optional.ofNullable(currentUserId);
    }

    void select(UUID userId) {
        currentUserId = userId;
    }

    void clear() {
        currentUserId = null;
    }
}
