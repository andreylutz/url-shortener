package shortener.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.StorageException;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkOpeningService;

class JsonLinkRepositoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String PREFIX = "https://short.local/";
    private static final Instant CREATED_AT = Instant.parse("2026-11-15T10:00:00.123456789Z");

    @TempDir Path directory;

    @Test
    void startsWithEmptyStateWhenFileDoesNotExist() {
        Path path = directory.resolve("new/storage.json");

        JsonLinkRepository repository = new JsonLinkRepository(path);

        assertTrue(repository.findAll().isEmpty());
        assertFalse(repository.userExists(OWNER));
        assertFalse(Files.exists(path));
    }

    @Test
    void restoresAllLinkFieldsAndUnreadNotifications() {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link().recordSuccessfulOpen().markExpirationNotified();
        Notification notification = Notification.expired(original, PREFIX);
        repository.add(original);
        repository.addNotification(notification);

        JsonLinkRepository restored = new JsonLinkRepository(path);

        assertTrue(restored.userExists(OWNER));
        assertEquals(original, restored.findByCode(original.code()).orElseThrow());
        assertEquals(List.of(original), restored.findByOwner(OWNER));
        assertEquals(List.of(notification), restored.drainNotifications(OWNER));
        assertTrue(new JsonLinkRepository(path).drainNotifications(OWNER).isEmpty());
    }

    @Test
    void keepsUserAndNotificationAfterLastLinkWasDeleted() {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link();
        repository.add(original);
        repository.addNotification(Notification.expired(original, PREFIX));
        repository.remove(original.code());

        JsonLinkRepository restored = new JsonLinkRepository(path);

        assertTrue(restored.findAll().isEmpty());
        assertTrue(restored.userExists(OWNER));
        assertEquals(1, restored.drainNotifications(OWNER).size());
    }

    @Test
    void rejectsDuplicateCodeWithoutChangingFileOrRegisteringNewOwner() throws Exception {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link();
        repository.add(original);
        byte[] before = Files.readAllBytes(path);
        ShortLink duplicate =
                new ShortLink(
                        original.code(),
                        OTHER_OWNER,
                        original.originalUri(),
                        3,
                        0,
                        original.createdAt(),
                        original.expiresAt());

        assertFalse(repository.add(duplicate));

        assertArrayEquals(before, Files.readAllBytes(path));
        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertFalse(restored.userExists(OTHER_OWNER));
        assertEquals(original, restored.findByCode(original.code()).orElseThrow());
    }

    @Test
    void commitsCompoundChangesOnlyAfterOuterOperationCompletes() throws Exception {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link().withVisitLimit(1);
        repository.add(original);
        String before = Files.readString(path);

        repository.executeExclusively(
                () -> {
                    ShortLink updated =
                            repository
                                    .update(original.code(), ShortLink::recordSuccessfulOpen)
                                    .orElseThrow();
                    assertEquals(before, assertDoesNotThrow(() -> Files.readString(path)));
                    repository.addNotification(Notification.limitReached(updated, PREFIX));
                    assertEquals(before, assertDoesNotThrow(() -> Files.readString(path)));
                    return null;
                });

        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertEquals(1, restored.findByCode(original.code()).orElseThrow().usedCount());
        assertEquals(1, restored.drainNotifications(OWNER).size());
    }

    @Test
    void savesExpirationNoticeEvenWhenOpeningIsRejected() {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link();
        repository.add(original);
        Clock clock = Clock.fixed(original.expiresAt(), ZoneOffset.UTC);
        LinkExpirationService expiration = new LinkExpirationService(repository, clock, PREFIX);
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        uri -> {
                            throw new AssertionError("Просроченная ссылка не должна открываться.");
                        },
                        expiration,
                        PREFIX,
                        original.code().length());

        assertThrows(IllegalStateException.class, () -> opening.open(PREFIX + original.code()));

        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertTrue(restored.findByCode(original.code()).orElseThrow().expirationNotified());
        assertEquals(1, restored.drainNotifications(OWNER).size());
        assertEquals(1, new LinkExpirationService(restored, clock, PREFIX).cleanup());
        JsonLinkRepository afterCleanup = new JsonLinkRepository(path);
        assertTrue(afterCleanup.findAll().isEmpty());
        assertTrue(afterCleanup.drainNotifications(OWNER).isEmpty());
    }

    @Test
    void validationFailureDoesNotDisableRepository() throws Exception {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link();
        repository.add(original);
        byte[] before = Files.readAllBytes(path);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repository.update(
                                original.code(),
                                stored ->
                                        new ShortLink(
                                                stored.code(),
                                                OTHER_OWNER,
                                                stored.originalUri(),
                                                3,
                                                0,
                                                stored.createdAt(),
                                                stored.expiresAt())));

        assertEquals(original, repository.findByCode(original.code()).orElseThrow());
        assertArrayEquals(before, Files.readAllBytes(path));
        repository.update(original.code(), ShortLink::recordSuccessfulOpen);
        assertEquals(
                1,
                new JsonLinkRepository(path).findByCode(original.code()).orElseThrow().usedCount());
    }

    @Test
    void failedWritePreservesFileAndStopsFurtherOperationsAfterBrowserCall() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        ShortLink original = link().withVisitLimit(1);
        repository.add(original);
        byte[] before = Files.readAllBytes(path);
        AtomicInteger browserCalls = new AtomicInteger();
        LinkExpirationService expiration =
                new LinkExpirationService(
                        repository, Clock.fixed(CREATED_AT, ZoneOffset.UTC), PREFIX);
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        uri -> browserCalls.incrementAndGet(),
                        expiration,
                        PREFIX,
                        original.code().length());
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(
                    directory,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assumeFalse(
                    Files.isWritable(directory), "Среда обходит ограничения доступа к каталогу.");

            assertThrows(StorageException.class, () -> opening.open(PREFIX + original.code()));

            assertEquals(1, browserCalls.get());
            assertArrayEquals(before, Files.readAllBytes(path));
            assertThrows(StorageException.class, repository::findAll);
            assertThrows(StorageException.class, () -> repository.add(link()));
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
        JsonLinkRepository restored = new JsonLinkRepository(path);
        assertEquals(original, restored.findByCode(original.code()).orElseThrow());
        assertTrue(restored.drainNotifications(OWNER).isEmpty());
    }

    private ShortLink link() {
        return new ShortLink(
                "Abc12345",
                OWNER,
                URI.create("https://example.org/Case/%2F?Token=AbC"),
                3,
                0,
                CREATED_AT,
                CREATED_AT.plus(Duration.ofDays(1)));
    }
}
