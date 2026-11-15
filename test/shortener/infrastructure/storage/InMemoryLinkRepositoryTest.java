package shortener.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shortener.core.model.ShortLink;

class InMemoryLinkRepositoryTest {
    private static final UUID FIRST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();

    @Test
    void rejectsOccupiedCodeWithoutRegisteringAnotherOwner() {
        ShortLink existing = link("Abc12345", FIRST_OWNER);
        assertTrue(repository.add(existing));

        boolean added = repository.add(link(existing.code(), SECOND_OWNER));

        assertFalse(added);
        assertEquals(existing, repository.findByCode(existing.code()).orElseThrow());
        assertTrue(repository.userExists(FIRST_OWNER));
        assertFalse(repository.userExists(SECOND_OWNER));
    }

    @Test
    void returnsOnlyLinksOfRequestedOwner() {
        ShortLink first = link("Abc12345", FIRST_OWNER);
        ShortLink second = link("Def67890", SECOND_OWNER);
        repository.add(first);
        repository.add(second);

        assertEquals(List.of(first), repository.findByOwner(FIRST_OWNER));
        assertEquals(List.of(second), repository.findByOwner(SECOND_OWNER));
    }

    @Test
    void returnsImmutableSnapshotOfOwnerLinks() {
        ShortLink first = link("Abc12345", FIRST_OWNER);
        repository.add(first);
        List<ShortLink> snapshot = repository.findByOwner(FIRST_OWNER);

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        repository.add(link("Def67890", FIRST_OWNER));

        assertEquals(List.of(first), snapshot);
        assertEquals(2, repository.findByOwner(FIRST_OWNER).size());
    }

    @Test
    void returnsEmptyResultForUnknownCode() {
        assertTrue(repository.findByCode("Unknown1").isEmpty());
    }

    @Test
    void updatesLinkWithoutChangingPreviouslyReturnedSnapshot() {
        ShortLink original = link("Abc12345", FIRST_OWNER);
        repository.add(original);
        List<ShortLink> snapshot = repository.findByOwner(FIRST_OWNER);

        ShortLink updated =
                repository.update(original.code(), ShortLink::recordSuccessfulOpen).orElseThrow();

        assertEquals(1, updated.usedCount());
        assertEquals(updated, repository.findByCode(original.code()).orElseThrow());
        assertEquals(0, snapshot.getFirst().usedCount());
    }

    @Test
    void keepsStoredLinkUnchangedWhenOperationFails() {
        ShortLink original = link("Abc12345", FIRST_OWNER);
        repository.add(original);
        IllegalStateException failure = new IllegalStateException("Ошибка операции.");

        IllegalStateException caught =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                repository.update(
                                        original.code(),
                                        stored -> {
                                            throw failure;
                                        }));

        assertSame(failure, caught);
        assertEquals(original, repository.findByCode(original.code()).orElseThrow());
    }

    @Test
    void doesNotCallOperationForUnknownCode() {
        assertTrue(
                repository
                        .update(
                                "Unknown1",
                                stored -> {
                                    throw new AssertionError(
                                            "Операция не должна вызываться для отсутствующей ссылки.");
                                })
                        .isEmpty());
    }

    @Test
    void rejectsOwnerChangeWithoutModifyingStoredLink() {
        ShortLink original = link("Abc12345", FIRST_OWNER);
        repository.add(original);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repository.update(
                                original.code(), stored -> link(stored.code(), SECOND_OWNER)));

        assertEquals(original, repository.findByCode(original.code()).orElseThrow());
        assertFalse(repository.userExists(SECOND_OWNER));
    }

    @Test
    void rejectsCodeChangeWithoutModifyingStoredLink() {
        ShortLink original = link("Abc12345", FIRST_OWNER);
        repository.add(original);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repository.update(
                                original.code(), stored -> link("Def67890", stored.ownerId())));

        assertEquals(original, repository.findByCode(original.code()).orElseThrow());
        assertTrue(repository.findByCode("Def67890").isEmpty());
    }

    private ShortLink link(String code, UUID ownerId) {
        return new ShortLink(
                code,
                ownerId,
                URI.create("https://example.org"),
                5,
                0,
                Instant.parse("2026-11-15T10:00:00Z"),
                Instant.parse("2026-11-16T10:00:00Z"));
    }
}
