package shortener.core.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.model.ShortLink;
import shortener.core.port.CodeGenerator;
import shortener.infrastructure.storage.InMemoryLinkRepository;

class ShortLinkServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final Duration LINK_TTL = Duration.ofDays(1);
    private static final int DEFAULT_VISIT_LIMIT = 5;
    private static final String URL = "https://example.org/Article?lang=ru";

    private InMemoryLinkRepository repository;
    private UserSession session;
    private ShortLinkService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLinkRepository();
        session = new UserSession();
        service = serviceWith(new SequenceCodeGenerator("Abc12345", "Def67890", "Ghi12345"));
    }

    @Test
    void createsLinkAndSelectsItsNewOwner() {
        assertTrue(service.currentUserId().isEmpty());

        ShortLink link = service.create(URL);

        assertAll(
                () -> assertEquals("Abc12345", link.code()),
                () -> assertEquals(URI.create(URL), link.originalUri()),
                () -> assertEquals(DEFAULT_VISIT_LIMIT, link.visitLimit()),
                () -> assertEquals(0, link.usedCount()),
                () -> assertEquals(CREATED_AT, link.createdAt()),
                () -> assertEquals(CREATED_AT.plus(LINK_TTL), link.expiresAt()),
                () -> assertEquals(link.ownerId(), service.currentUserId().orElseThrow()),
                () -> assertTrue(repository.userExists(link.ownerId())),
                () -> assertEquals(link, repository.findByCode(link.code()).orElseThrow()));
    }

    @Test
    void usesRequestedVisitLimit() {
        ShortLink link = service.create(URL, 12);

        assertEquals(12, link.visitLimit());
    }

    @Test
    void givesDifferentUsersDifferentCodesForSameUrl() {
        ShortLink first = service.create(URL);
        service.startNewUser();

        ShortLink second = service.create(URL);

        assertNotEquals(first.ownerId(), second.ownerId());
        assertNotEquals(first.code(), second.code());
        assertEquals(List.of(first), repository.findByOwner(first.ownerId()));
        assertEquals(List.of(second), repository.findByOwner(second.ownerId()));
    }

    @Test
    void createsNewLinkWhenSameOwnerShortensSameUrlAgain() {
        ShortLink first = service.create(URL);

        ShortLink second = service.create(URL);

        assertEquals(first.ownerId(), second.ownerId());
        assertNotEquals(first.code(), second.code());
        assertEquals(List.of(first, second), repository.findByOwner(first.ownerId()));
    }

    @Test
    void keepsSeveralUrlsForOneOwner() {
        ShortLink first = service.create(URL);

        ShortLink second = service.create("https://example.org/Other");

        assertEquals(first.ownerId(), second.ownerId());
        assertEquals(List.of(first, second), repository.findByOwner(first.ownerId()));
    }

    @Test
    void selectsExistingUserForSubsequentCreation() {
        ShortLink first = service.create(URL);
        service.startNewUser();
        ShortLink second = service.create(URL);

        UUID selected = service.selectUser(first.ownerId().toString());
        ShortLink third = service.create("https://example.org/Third");

        assertEquals(first.ownerId(), selected);
        assertEquals(first.ownerId(), third.ownerId());
        assertEquals(List.of(first, third), repository.findByOwner(selected));
        assertEquals(List.of(second), repository.findByOwner(second.ownerId()));
    }

    @Test
    void acceptsCanonicalUuidInUpperCase() {
        ShortLink link = service.create(URL);
        service.startNewUser();

        UUID selected = service.selectUser(link.ownerId().toString().toUpperCase(Locale.ROOT));

        assertEquals(link.ownerId(), selected);
    }

    @Test
    void startsNewUserWithoutImmediatelyCreatingUuid() {
        ShortLink link = service.create(URL);

        service.startNewUser();

        assertTrue(service.currentUserId().isEmpty());
        assertTrue(repository.userExists(link.ownerId()));
    }

    @Test
    void rejectsUnknownUserWithoutChangingSession() {
        ShortLink link = service.create(URL);
        UUID unknownUser = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThrows(
                IllegalArgumentException.class, () -> service.selectUser(unknownUser.toString()));

        assertEquals(link.ownerId(), service.currentUserId().orElseThrow());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid", "1-1-1-1-1", "550e8400-e29b-41d4-a716-44665544000Z"})
    void rejectsMalformedUuidWithoutChangingSession(String value) {
        ShortLink link = service.create(URL);

        assertThrows(IllegalArgumentException.class, () -> service.selectUser(value));

        assertEquals(link.ownerId(), service.currentUserId().orElseThrow());
    }

    @Test
    void retriesCollisionsWithoutReplacingExistingLink() {
        ShortLink existing = service.create(URL);
        service.startNewUser();
        SequenceCodeGenerator generator =
                new SequenceCodeGenerator(existing.code(), existing.code(), "Unique99");
        ShortLinkService collidingService = serviceWith(generator);

        ShortLink created = collidingService.create("https://example.org/New");

        assertEquals(3, generator.calls);
        assertEquals("Unique99", created.code());
        assertNotEquals(existing.ownerId(), created.ownerId());
        assertEquals(existing, repository.findByCode(existing.code()).orElseThrow());
        assertEquals(created, repository.findByCode(created.code()).orElseThrow());
    }

    @Test
    void stopsAfterRepeatedCollisionsWithoutSelectingNewUser() {
        ShortLink existing = service.create(URL);
        service.startNewUser();
        AtomicInteger calls = new AtomicInteger();
        ShortLinkService collidingService =
                serviceWith(
                        () -> {
                            calls.incrementAndGet();
                            return existing.code();
                        });

        assertThrows(IllegalStateException.class, () -> collidingService.create(URL));

        assertEquals(100, calls.get());
        assertTrue(collidingService.currentUserId().isEmpty());
        assertEquals(existing, repository.findByCode(existing.code()).orElseThrow());
    }

    @Test
    void preservesPathQueryAndEscapedCharacters() {
        String url = "https://EXAMPLE.org/CaseSensitive/%2F?Token=AbC&x=1#Section";

        ShortLink link = service.create(url);

        assertEquals(url, link.originalUri().toString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.org",
                "https://example.org/Path",
                "https://[::1]:8080/Path?q=AbC"
            })
    void acceptsAbsoluteHttpAndHttpsUris(String url) {
        ShortLink link = service.create(url);

        assertEquals(url, link.originalUri().toString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "/relative",
                "example.org/path",
                "ftp://example.org/file",
                "javascript:alert(1)",
                "https:///path",
                "https://example.org/a b",
                "https://example.org/%GG",
                "https://example.org:0/",
                "https://example.org:65536/"
            })
    void rejectsInvalidUrlWithoutCreatingUserOrLink(String url) {
        assertThrows(IllegalArgumentException.class, () -> service.create(url));

        assertTrue(service.currentUserId().isEmpty());
        assertTrue(repository.findByCode("Abc12345").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidVisitLimitWithoutCreatingUserOrLink(int limit) {
        assertThrows(IllegalArgumentException.class, () -> service.create(URL, limit));

        assertTrue(service.currentUserId().isEmpty());
        assertTrue(repository.findByCode("Abc12345").isEmpty());
    }

    @Test
    void keepsExistingSessionAfterInvalidCreation() {
        ShortLink existing = service.create(URL);

        assertThrows(IllegalArgumentException.class, () -> service.create("not-a-url"));

        assertEquals(existing.ownerId(), service.currentUserId().orElseThrow());
        assertEquals(List.of(existing), repository.findByOwner(existing.ownerId()));
    }

    @Test
    void keepsSessionEmptyWhenGeneratorFails() {
        ShortLinkService failingService =
                serviceWith(
                        () -> {
                            throw new IllegalStateException("Ошибка генератора.");
                        });

        assertThrows(IllegalStateException.class, () -> failingService.create(URL));

        assertTrue(failingService.currentUserId().isEmpty());
    }

    private ShortLinkService serviceWith(CodeGenerator generator) {
        return new ShortLinkService(
                repository,
                session,
                generator,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                DEFAULT_VISIT_LIMIT,
                LINK_TTL);
    }

    private static final class SequenceCodeGenerator implements CodeGenerator {
        private final String[] codes;
        private int calls;

        private SequenceCodeGenerator(String... codes) {
            this.codes = codes;
        }

        @Override
        public String generate() {
            return codes[calls++];
        }
    }
}
