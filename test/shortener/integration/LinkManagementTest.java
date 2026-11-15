package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class LinkManagementTest {
    @Test
    void managesLinksOfTwoUsersWithoutSharingTheirRightsOrCounters() {
        AppConfig config = new ConfigLoader().load(Path.of("config/application.properties"));
        InMemoryLinkRepository repository = new InMemoryLinkRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
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
        String firstAddress = config.shortLinkPrefix() + first.code();
        opening.open(firstAddress);
        assertEquals(1, users.readNotifications().size());
        users.startNewUser();
        ShortLink second = users.create("https://example.org/Original", 3);
        assertThrows(
                IllegalStateException.class, () -> management.changeVisitLimit(firstAddress, 3));
        assertThrows(IllegalStateException.class, () -> management.deleteOwnLink(firstAddress));

        users.selectUser(first.ownerId().toString());
        management.changeVisitLimit(firstAddress, 3);
        URI replacement = URI.create("https://example.org/New?Token=AbC");
        ShortLink changed = management.changeOriginalUrl(firstAddress, replacement.toString());
        opening.open(firstAddress);
        opening.open(firstAddress);
        assertThrows(IllegalStateException.class, () -> opening.open(firstAddress));

        assertEquals(1, changed.usedCount());
        assertEquals(first.createdAt(), changed.createdAt());
        assertEquals(first.expiresAt(), changed.expiresAt());
        assertEquals(first.ownerId(), changed.ownerId());
        assertEquals(List.of(first.originalUri(), replacement, replacement), openedUris);
        assertEquals(1, users.readNotifications().size());
        management.deleteOwnLink(firstAddress);
        assertTrue(management.listOwnLinks().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeOriginalUrl(firstAddress, replacement.toString()));
        users.selectUser(second.ownerId().toString());
        assertEquals(List.of(second), management.listOwnLinks());
        assertEquals(
                0, management.getOwnLink(config.shortLinkPrefix() + second.code()).usedCount());
    }
}
