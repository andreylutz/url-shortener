package shortener.core.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.model.ShortLink;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class LinkManagementServiceTest {
    private static final String PREFIX = "https://short.local/";
    private static final String URL = "https://example.org/Article";
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00Z");

    private InMemoryLinkRepository repository;
    private MutableClock clock;
    private ShortLinkService users;
    private LinkManagementService management;
    private LinkExpirationService expiration;
    private LinkOpeningService opening;
    private List<URI> openedUris;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLinkRepository();
        clock = new MutableClock(CREATED_AT);
        UserSession session = new UserSession();
        Deque<String> codes = new ArrayDeque<>(List.of("Abc12345", "Def67890", "Ghi12345"));
        users =
                new ShortLinkService(
                        repository, session, codes::removeFirst, clock, 5, Duration.ofDays(1));
        expiration = new LinkExpirationService(repository, clock, PREFIX);
        management = new LinkManagementService(repository, session, expiration, PREFIX, 8);
        openedUris = new ArrayList<>();
        opening = new LinkOpeningService(repository, openedUris::add, expiration, PREFIX, 8);
    }

    @Test
    void listsOnlyCurrentOwnersLinksAndReturnsImmutableSnapshot() {
        ShortLink first = users.create(URL);
        ShortLink second = users.create("https://example.org/Second");
        users.startNewUser();
        ShortLink other = users.create(URL);

        assertEquals(List.of(other), management.listOwnLinks());
        users.selectUser(first.ownerId().toString());
        List<ShortLink> own = management.listOwnLinks();

        assertEquals(List.of(first, second), own);
        assertThrows(UnsupportedOperationException.class, own::clear);
    }

    @Test
    void showsOwnLinkParameters() {
        ShortLink link = users.create(URL);

        assertEquals(link, management.getOwnLink(address(link)));
    }

    @Test
    void changesLimitWithoutResettingCounterOrLifetime() {
        ShortLink link = users.create(URL, 2);
        opening.open(address(link));

        ShortLink changed = management.changeVisitLimit(address(link), 7);

        assertAll(
                () -> assertEquals(7, changed.visitLimit()),
                () -> assertEquals(1, changed.usedCount()),
                () -> assertEquals(link.code(), changed.code()),
                () -> assertEquals(link.ownerId(), changed.ownerId()),
                () -> assertEquals(link.originalUri(), changed.originalUri()),
                () -> assertEquals(link.createdAt(), changed.createdAt()),
                () -> assertEquals(link.expiresAt(), changed.expiresAt()));
    }

    @Test
    void canDecreaseLimitWhileKeepingItAboveUsedCount() {
        ShortLink link = users.create(URL, 10);
        opening.open(address(link));
        opening.open(address(link));

        ShortLink changed = management.changeVisitLimit(address(link), 3);

        assertEquals(2, changed.usedCount());
        assertEquals(3, changed.visitLimit());
        assertEquals(3, opening.open(address(link)).usedCount());
        assertThrows(IllegalStateException.class, () -> opening.open(address(link)));
    }

    @Test
    void increasingExhaustedLimitRestoresAccessWithoutResettingCounter() {
        ShortLink link = users.create(URL, 1);
        opening.open(address(link));
        users.readNotifications();
        assertThrows(IllegalStateException.class, () -> opening.open(address(link)));

        ShortLink changed = management.changeVisitLimit(address(link), 2);

        assertEquals(1, changed.usedCount());
        assertEquals(2, opening.open(address(link)).usedCount());
        assertThrows(IllegalStateException.class, () -> opening.open(address(link)));
        assertEquals(1, users.readNotifications().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 2})
    void rejectsNewLimitNotGreaterThanUsedCountWithoutChangingState(int newLimit) {
        ShortLink link = users.create(URL, 5);
        opening.open(address(link));
        ShortLink before = opening.open(address(link));

        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeVisitLimit(address(link), newLimit));

        assertEquals(before, management.getOwnLink(address(link)));
    }

    @Test
    void changesUrlAndPreservesAllOtherParameters() {
        ShortLink link = users.create(URL);
        opening.open(address(link));
        String newUrl = "https://EXAMPLE.org/Case/%2F?Token=AbC#Section";

        ShortLink changed = management.changeOriginalUrl(address(link), newUrl);
        opening.open(address(link));

        assertAll(
                () -> assertEquals(newUrl, changed.originalUri().toString()),
                () -> assertEquals(1, changed.usedCount()),
                () -> assertEquals(link.code(), changed.code()),
                () -> assertEquals(link.ownerId(), changed.ownerId()),
                () -> assertEquals(link.visitLimit(), changed.visitLimit()),
                () -> assertEquals(link.createdAt(), changed.createdAt()),
                () -> assertEquals(link.expiresAt(), changed.expiresAt()),
                () -> assertEquals(List.of(link.originalUri(), URI.create(newUrl)), openedUris));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "/relative",
                "ftp://example.org/file",
                "https:///path",
                "https://example.org/a b",
                "https://example.org/%GG"
            })
    void rejectsInvalidNewUrlWithoutChangingLink(String newUrl) {
        ShortLink original = users.create(URL);

        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeOriginalUrl(address(original), newUrl));

        assertEquals(original, management.getOwnLink(address(original)));
    }

    @Test
    void deletesOnlyRequestedOwnLinkAndKeepsUser() {
        ShortLink deleted = users.create(URL);
        ShortLink remaining = users.create("https://example.org/Remaining");

        management.deleteOwnLink(address(deleted));

        assertTrue(repository.findByCode(deleted.code()).isEmpty());
        assertEquals(List.of(remaining), management.listOwnLinks());
        assertTrue(repository.userExists(deleted.ownerId()));
        assertTrue(users.readNotifications().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> opening.open(address(deleted)));
    }

    @Test
    void rejectsViewingForeignLink() {
        ShortLink foreign = createForeignLink();

        assertThrows(IllegalStateException.class, () -> management.getOwnLink(address(foreign)));

        assertEquals(foreign, repository.findByCode(foreign.code()).orElseThrow());
    }

    @Test
    void rejectsChangingForeignLimit() {
        ShortLink foreign = createForeignLink();

        assertThrows(
                IllegalStateException.class,
                () -> management.changeVisitLimit(address(foreign), 10));
        assertThrows(
                IllegalStateException.class,
                () -> management.changeVisitLimit(address(foreign), 0));

        assertEquals(foreign, repository.findByCode(foreign.code()).orElseThrow());
    }

    @Test
    void rejectsChangingForeignUrl() {
        ShortLink foreign = createForeignLink();

        assertThrows(
                IllegalStateException.class,
                () -> management.changeOriginalUrl(address(foreign), "https://example.org/New"));
        assertThrows(
                IllegalStateException.class,
                () -> management.changeOriginalUrl(address(foreign), "invalid"));

        assertEquals(foreign, repository.findByCode(foreign.code()).orElseThrow());
    }

    @Test
    void rejectsDeletingForeignLink() {
        ShortLink foreign = createForeignLink();

        assertThrows(IllegalStateException.class, () -> management.deleteOwnLink(address(foreign)));

        assertEquals(foreign, repository.findByCode(foreign.code()).orElseThrow());
    }

    @Test
    void usesCurrentSessionForEveryOwnershipCheck() {
        ShortLink first = users.create(URL);
        users.startNewUser();
        ShortLink second = users.create(URL);
        assertThrows(
                IllegalStateException.class, () -> management.changeVisitLimit(address(first), 10));

        users.selectUser(first.ownerId().toString());
        ShortLink changed = management.changeVisitLimit(address(first), 10);

        assertEquals(first.ownerId(), changed.ownerId());
        assertThrows(IllegalStateException.class, () -> management.deleteOwnLink(address(second)));
        assertEquals(second, repository.findByCode(second.code()).orElseThrow());
    }

    @Test
    void requiresSelectedUserForAllManagementOperations() {
        ShortLink link = users.create(URL);
        users.startNewUser();

        assertAll(
                () -> assertThrows(IllegalStateException.class, management::listOwnLinks),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> management.getOwnLink(address(link))),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> management.changeVisitLimit(address(link), 10)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> management.changeOriginalUrl(address(link), URL)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> management.deleteOwnLink(address(link))));
        assertEquals(link, repository.findByCode(link.code()).orElseThrow());
    }

    @Test
    void rejectsUnknownLinkForViewingEditingAndDeletion() {
        users.create(URL);
        String unknown = PREFIX + "Unknown1";

        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.getOwnLink(unknown)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.changeVisitLimit(unknown, 10)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.changeOriginalUrl(unknown, URL)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.deleteOwnLink(unknown)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {"Abc12345", "https://other.local/Abc12345", "https://short.local/Abc1234!"})
    void rejectsMalformedShortLinksForAllSingleLinkOperations(String value) {
        ShortLink link = users.create(URL);

        assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class, () -> management.getOwnLink(value)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.changeVisitLimit(value, 10)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.changeOriginalUrl(value, URL)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> management.deleteOwnLink(value)));
        assertEquals(link, repository.findByCode(link.code()).orElseThrow());
    }

    @Test
    void rejectsEditingExpiredLinkWithoutRestoringIt() {
        ShortLink link = users.create(URL, 2);
        ShortLink before = opening.open(address(link));
        clock.set(link.expiresAt());

        assertThrows(
                IllegalStateException.class,
                () -> management.changeVisitLimit(address(link), Integer.MAX_VALUE));
        assertThrows(
                IllegalStateException.class,
                () -> management.changeOriginalUrl(address(link), "https://example.org/New"));
        assertThrows(
                IllegalStateException.class,
                () -> management.changeOriginalUrl(address(link), "invalid"));

        ShortLink stored = repository.findByCode(link.code()).orElseThrow();
        assertEquals(before.markExpirationNotified(), stored);
        assertThrows(IllegalStateException.class, () -> opening.open(address(link)));
        assertEquals(1, users.readNotifications().size());
        assertEquals(1, expiration.cleanup());
        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeVisitLimit(address(link), 10));
        assertTrue(users.readNotifications().isEmpty());
    }

    @Test
    void viewsExpiredParametersUntilCleanupRemovesRecord() {
        ShortLink link = users.create(URL);
        clock.set(link.expiresAt());

        assertEquals(link, management.getOwnLink(address(link)));
        assertEquals(List.of(link), management.listOwnLinks());
        expiration.cleanup();

        assertTrue(management.listOwnLinks().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> management.getOwnLink(address(link)));
    }

    @Test
    void preservesExpirationNotificationWhenOwnerDeletesExpiredRecord() {
        ShortLink link = users.create(URL);
        clock.set(link.expiresAt());

        management.deleteOwnLink(address(link));

        assertTrue(repository.findByCode(link.code()).isEmpty());
        assertEquals(1, users.readNotifications().size());
        assertEquals(0, expiration.cleanup());
        assertTrue(users.readNotifications().isEmpty());
    }

    @Test
    void cannotRestoreDeletedLinkByEditing() {
        ShortLink link = users.create(URL);
        management.deleteOwnLink(address(link));

        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeVisitLimit(address(link), 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> management.changeOriginalUrl(address(link), URL));

        assertTrue(repository.findByCode(link.code()).isEmpty());
        assertTrue(management.listOwnLinks().isEmpty());
    }

    private ShortLink createForeignLink() {
        ShortLink foreign = users.create(URL);
        users.startNewUser();
        users.create("https://example.org/Other");
        return foreign;
    }

    private String address(ShortLink link) {
        return PREFIX + link.code();
    }
}
