package shortener.infrastructure.storage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;

record StorageSnapshot(
        int version, List<UUID> users, List<ShortLink> links, List<Notification> notifications) {
    static final int CURRENT_VERSION = 1;

    StorageSnapshot {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Неподдерживаемая версия данных: " + version);
        }
        if (users == null || links == null || notifications == null) {
            throw new IllegalArgumentException(
                    "В данных обязательны пользователи, ссылки и уведомления.");
        }
        users = List.copyOf(users);
        links = List.copyOf(links);
        notifications = List.copyOf(notifications);
        Set<UUID> knownUsers = new HashSet<>();
        for (UUID user : users) {
            if (!knownUsers.add(user)) {
                throw new IllegalArgumentException("UUID пользователя повторяется: " + user);
            }
        }
        Set<String> codes = new HashSet<>();
        for (ShortLink link : links) {
            if (!codes.add(link.code())) {
                throw new IllegalArgumentException("Короткий код повторяется: " + link.code());
            }
            if (!knownUsers.contains(link.ownerId())) {
                throw new IllegalArgumentException("Не найден владелец ссылки: " + link.code());
            }
        }
        for (Notification notification : notifications) {
            if (!knownUsers.contains(notification.ownerId())) {
                throw new IllegalArgumentException(
                        "Не найден получатель уведомления: " + notification.ownerId());
            }
        }
    }

    static StorageSnapshot empty() {
        return new StorageSnapshot(CURRENT_VERSION, List.of(), List.of(), List.of());
    }
}
