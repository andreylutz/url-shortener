package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import shortener.core.model.ShortLink;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.storage.InMemoryLinkRepository;

class LinkCreationTest {
    @Test
    void createsLinksForTwoUsersUsingApplicationConfiguration() {
        AppConfig config = new ConfigLoader().load(Path.of("config/application.properties"));
        InMemoryLinkRepository repository = new InMemoryLinkRepository();
        RandomCodeGenerator generator =
                new RandomCodeGenerator(config.codeLength(), new Random(42));
        Instant now = Instant.parse("2026-11-15T10:00:00Z");
        ShortLinkService service =
                new ShortLinkService(
                        repository,
                        new UserSession(),
                        generator,
                        Clock.fixed(now, ZoneOffset.UTC),
                        config.defaultVisitLimit(),
                        config.linkTtl());

        ShortLink first = service.create("https://example.org/Article?Token=AbC");
        service.startNewUser();
        ShortLink second = service.create("https://example.org/Article?Token=AbC");
        service.selectUser(first.ownerId().toString());
        ShortLink third = service.create("https://example.org/Other");

        assertEquals(config.codeLength(), first.code().length());
        assertTrue(first.code().matches("[A-Za-z0-9]+"));
        assertEquals(config.defaultVisitLimit(), first.visitLimit());
        assertEquals(now.plus(config.linkTtl()), first.expiresAt());
        assertNotEquals(first.code(), second.code());
        assertNotEquals(first.ownerId(), second.ownerId());
        assertEquals(first.ownerId(), third.ownerId());
        assertEquals(List.of(first, third), repository.findByOwner(first.ownerId()));
        assertEquals(List.of(second), repository.findByOwner(second.ownerId()));
    }
}
