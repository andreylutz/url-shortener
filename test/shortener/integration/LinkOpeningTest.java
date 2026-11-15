package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.storage.InMemoryLinkRepository;

class LinkOpeningTest {
    @Test
    void createsAndOpensLinkUntilLimitWithoutRequiringOwnerSession() {
        AppConfig config = new ConfigLoader().load(Path.of("config/application.properties"));
        InMemoryLinkRepository repository = new InMemoryLinkRepository();
        ShortLinkService creation =
                new ShortLinkService(
                        repository,
                        new UserSession(),
                        new RandomCodeGenerator(config.codeLength(), new Random(42)),
                        Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC),
                        config.defaultVisitLimit(),
                        config.linkTtl());
        List<URI> openedUris = new ArrayList<>();
        LinkExpirationService expiration =
                new LinkExpirationService(
                        repository,
                        Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC),
                        config.shortLinkPrefix());
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        openedUris::add,
                        expiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        ShortLink link = creation.create("https://example.org/Article?Token=AbC", 2);
        creation.startNewUser();
        String shortLink = config.shortLinkPrefix() + link.code();

        opening.open(shortLink);
        opening.open(shortLink);
        assertThrows(IllegalStateException.class, () -> opening.open(shortLink));

        assertEquals(List.of(link.originalUri(), link.originalUri()), openedUris);
        assertEquals(2, repository.findByCode(link.code()).orElseThrow().usedCount());
    }
}
