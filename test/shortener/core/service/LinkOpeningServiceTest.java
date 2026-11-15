package shortener.core.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.model.ShortLink;
import shortener.core.port.BrowserException;
import shortener.core.port.BrowserOpener;
import shortener.infrastructure.storage.InMemoryLinkRepository;

class LinkOpeningServiceTest {
    private static final String PREFIX = "https://short.local/";
    private static final String CODE = "Abc12345";
    private static final String SHORT_LINK = PREFIX + CODE;
    private static final URI ORIGINAL_URI =
            URI.create("https://example.org/Case/%2F?Token=AbC#Section");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-16T10:00:00Z");

    private InMemoryLinkRepository repository;
    private RecordingBrowser browser;
    private LinkExpirationService expiration;
    private LinkOpeningService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLinkRepository();
        browser = new RecordingBrowser();
        expiration =
                new LinkExpirationService(
                        repository, Clock.fixed(CREATED_AT, ZoneOffset.UTC), PREFIX);
        service = new LinkOpeningService(repository, browser, expiration, PREFIX, CODE.length());
    }

    @Test
    void opensOriginalUriAndIncrementsOnlyUsage() {
        ShortLink original = storeLink(3, 0);

        ShortLink opened = service.open(SHORT_LINK);

        assertAll(
                () -> assertEquals(List.of(ORIGINAL_URI), browser.openedUris),
                () -> assertEquals(1, opened.usedCount()),
                () -> assertEquals(original.code(), opened.code()),
                () -> assertEquals(original.ownerId(), opened.ownerId()),
                () -> assertEquals(original.originalUri(), opened.originalUri()),
                () -> assertEquals(original.visitLimit(), opened.visitLimit()),
                () -> assertEquals(original.createdAt(), opened.createdAt()),
                () -> assertEquals(original.expiresAt(), opened.expiresAt()),
                () -> assertEquals(opened, repository.findByCode(CODE).orElseThrow()));
    }

    @Test
    void incrementsUsageAfterBrowserReturnsSuccessfully() {
        storeLink(3, 0);
        BrowserOpener checkingBrowser =
                uri -> assertEquals(0, repository.findByCode(CODE).orElseThrow().usedCount());
        LinkOpeningService checkingService =
                new LinkOpeningService(
                        repository, checkingBrowser, expiration, PREFIX, CODE.length());

        checkingService.open(SHORT_LINK);

        assertEquals(1, repository.findByCode(CODE).orElseThrow().usedCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void allowsExactlyNOpeningsAndBlocksFollowingAttempts(int limit) {
        storeLink(limit, 0);

        for (int used = 1; used <= limit; used++) {
            assertEquals(used, service.open(SHORT_LINK).usedCount());
        }
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));
        assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));

        assertEquals(limit, browser.calls);
        assertEquals(limit, repository.findByCode(CODE).orElseThrow().usedCount());
        assertTrue(exception.getMessage().contains(SHORT_LINK));
        assertTrue(exception.getMessage().contains("лимит"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Ошибка системного вызова.",
                "Java Desktop не поддерживается.",
                "Действие BROWSE не поддерживается."
            })
    void keepsUsageUnchangedWhenBrowserFails(String reason) {
        ShortLink original = storeLink(1, 0);
        browser.failure = new BrowserException(reason);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));

        assertSame(browser.failure, exception.getCause());
        assertTrue(exception.getMessage().contains(SHORT_LINK));
        assertTrue(exception.getMessage().contains(reason));
        assertEquals(original, repository.findByCode(CODE).orElseThrow());
        assertEquals(1, browser.calls);
        assertTrue(browser.openedUris.isEmpty());
    }

    @Test
    void allowsRetryAfterFailedBrowserCallWithoutLosingLastOpening() {
        storeLink(1, 0);
        browser.failure = new BrowserException("Браузер недоступен.");
        assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));

        browser.failure = null;
        ShortLink opened = service.open(SHORT_LINK);

        assertEquals(1, opened.usedCount());
        assertEquals(2, browser.calls);
        assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));
        assertEquals(2, browser.calls);
    }

    @Test
    void continuesCountingFromStoredUsage() {
        storeLink(5, 4);

        assertEquals(5, service.open(SHORT_LINK).usedCount());
        assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));

        assertEquals(1, browser.calls);
    }

    @Test
    void handlesMaximumIntegerLimitWithoutCounterOverflow() {
        storeLink(Integer.MAX_VALUE, Integer.MAX_VALUE - 1);

        assertEquals(Integer.MAX_VALUE, service.open(SHORT_LINK).usedCount());
        assertThrows(IllegalStateException.class, () -> service.open(SHORT_LINK));

        assertEquals(Integer.MAX_VALUE, repository.findByCode(CODE).orElseThrow().usedCount());
        assertEquals(1, browser.calls);
    }

    @Test
    void rejectsUnknownLinkWithoutCallingBrowser() {
        String unknown = PREFIX + "Unknown1";

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.open(unknown));

        assertTrue(exception.getMessage().contains(unknown));
        assertEquals(0, browser.calls);
    }

    @Test
    void treatsShortCodeAsCaseSensitive() {
        storeLink(3, 0);

        assertThrows(IllegalArgumentException.class, () -> service.open(PREFIX + "abc12345"));

        assertEquals(0, browser.calls);
        assertEquals(0, repository.findByCode(CODE).orElseThrow().usedCount());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "Abc12345",
                "https://other.local/Abc12345",
                "https://short.local/Abc1234",
                "https://short.local/Abc123456",
                "https://short.local/Abc1234!",
                "https://short.local/Abc12345?x=1"
            })
    void rejectsInvalidShortLinkWithoutCallingBrowser(String value) {
        ShortLink original = storeLink(3, 0);

        assertThrows(IllegalArgumentException.class, () -> service.open(value));

        assertEquals(0, browser.calls);
        assertEquals(original, repository.findByCode(CODE).orElseThrow());
    }

    @Test
    void changesUsageOnlyForOpenedLink() {
        storeLink(3, 0);
        ShortLink other =
                new ShortLink(
                        "Def67890",
                        OWNER_ID,
                        URI.create("https://example.org/Other"),
                        5,
                        0,
                        CREATED_AT,
                        EXPIRES_AT);
        repository.add(other);

        service.open(SHORT_LINK);

        assertEquals(1, repository.findByCode(CODE).orElseThrow().usedCount());
        assertEquals(other, repository.findByCode(other.code()).orElseThrow());
    }

    private ShortLink storeLink(int limit, int usedCount) {
        ShortLink link =
                new ShortLink(
                        CODE, OWNER_ID, ORIGINAL_URI, limit, usedCount, CREATED_AT, EXPIRES_AT);
        repository.add(link);
        return link;
    }

    private static final class RecordingBrowser implements BrowserOpener {
        private final List<URI> openedUris = new ArrayList<>();
        private BrowserException failure;
        private int calls;

        @Override
        public void open(URI uri) throws BrowserException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            openedUris.add(uri);
        }
    }
}
