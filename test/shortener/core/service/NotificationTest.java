package shortener.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.infrastructure.storage.InMemoryLinkRepository;

class NotificationTest {
    @Test
    void readsOnlyCurrentOwnersNotificationsOnce() {
        InMemoryLinkRepository repository = new InMemoryLinkRepository();
        String[] codes = {"Abc12345", "Def67890"};
        int[] next = {0};
        ShortLinkService users =
                new ShortLinkService(
                        repository,
                        new UserSession(),
                        () -> codes[next[0]++],
                        Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC),
                        3,
                        Duration.ofDays(1));
        ShortLink first = users.create("https://example.org/First");
        users.startNewUser();
        ShortLink second = users.create("https://example.org/Second");
        Notification firstNotice = Notification.limitReached(first, "https://short.local/");
        Notification secondNotice = Notification.limitReached(second, "https://short.local/");
        repository.addNotification(firstNotice);
        repository.addNotification(secondNotice);

        List<Notification> secondOwners = users.readNotifications();

        assertEquals(List.of(secondNotice), secondOwners);
        assertThrows(UnsupportedOperationException.class, secondOwners::clear);
        assertTrue(users.readNotifications().isEmpty());
        users.selectUser(first.ownerId().toString());
        assertEquals(List.of(firstNotice), users.readNotifications());
        assertTrue(users.readNotifications().isEmpty());
    }

    @Test
    void requiresSelectedUserToReadNotifications() {
        ShortLinkService users =
                new ShortLinkService(
                        new InMemoryLinkRepository(),
                        new UserSession(),
                        () -> "Abc12345",
                        Clock.systemUTC(),
                        3,
                        Duration.ofDays(1));

        assertThrows(IllegalStateException.class, users::readNotifications);
    }
}
