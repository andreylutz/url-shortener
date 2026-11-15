package shortener.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.port.StorageException;

class JsonStorageFileTest {
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_OWNER = "00000000-0000-0000-0000-000000000002";
    private static final String DOCUMENT =
            """
            {
              "version": 1,
              "users": ["00000000-0000-0000-0000-000000000001"],
              "links": [{
                "code": "Abc12345",
                "ownerId": "00000000-0000-0000-0000-000000000001",
                "originalUri": "https://example.org/Article",
                "visitLimit": 3,
                "usedCount": 1,
                "createdAt": "2026-11-15T10:00:00Z",
                "expiresAt": "2026-11-16T10:00:00Z",
                "expirationNotified": false
              }],
              "notifications": []
            }
            """;

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{",
                "null",
                "[]",
                "{}",
                "{\"version\":1,\"version\":1,\"users\":[],\"links\":[],\"notifications\":[]}"
            })
    void doesNotReplaceMalformedExistingFile(String contents) throws Exception {
        Path path = directory.resolve("storage.json");
        Files.writeString(path, contents);

        StorageException failure =
                assertThrows(StorageException.class, () -> new JsonLinkRepository(path));

        assertTrue(failure.getMessage().contains(path.toString()));
        assertEquals(contents, Files.readString(path));
    }

    @Test
    void rejectsTrailingJsonValueWithoutOverwritingFile() throws Exception {
        Path path = directory.resolve("storage.json");
        String contents = DOCUMENT + "{}";
        Files.writeString(path, contents);

        assertThrows(StorageException.class, () -> new JsonLinkRepository(path));

        assertEquals(contents, Files.readString(path));
    }

    @Test
    void reportsUnsupportedFormatVersion() throws Exception {
        Path path = directory.resolve("storage.json");
        Files.writeString(path, DOCUMENT.replace("\"version\": 1", "\"version\": 2"));

        StorageException failure =
                assertThrows(StorageException.class, () -> new JsonLinkRepository(path));

        assertTrue(failure.getMessage().contains("версия"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "duplicateUser",
                "duplicateCode",
                "unknownOwner",
                "invalidUuid",
                "invalidCode",
                "invalidUrl",
                "negativeLimit",
                "negativeCounter",
                "counterAboveLimit",
                "invalidExpiration",
                "missingFlag",
                "nullCounter",
                "fractionalLimit",
                "textLimit",
                "missingUsers",
                "nullLink",
                "unknownField",
                "unknownRecipient"
            })
    void rejectsInvalidStateWithoutOverwritingFile(String problem) throws Exception {
        ObjectNode document = (ObjectNode) new ObjectMapper().readTree(DOCUMENT);
        ObjectNode link = (ObjectNode) document.withArray("links").get(0);
        switch (problem) {
            case "duplicateUser" -> document.withArray("users").add(OWNER);
            case "duplicateCode" -> document.withArray("links").add(link.deepCopy());
            case "unknownOwner" -> link.put("ownerId", OTHER_OWNER);
            case "invalidUuid" -> link.put("ownerId", "not-a-uuid");
            case "invalidCode" -> link.put("code", "bad-code");
            case "invalidUrl" -> link.put("originalUri", "ftp://example.org/file");
            case "negativeLimit" -> link.put("visitLimit", -1);
            case "negativeCounter" -> link.put("usedCount", -1);
            case "counterAboveLimit" -> link.put("usedCount", 4);
            case "invalidExpiration" -> link.put("expiresAt", "2026-11-14T10:00:00Z");
            case "missingFlag" -> link.remove("expirationNotified");
            case "nullCounter" -> link.putNull("usedCount");
            case "fractionalLimit" -> link.put("visitLimit", 2.5);
            case "textLimit" -> link.put("visitLimit", "3");
            case "missingUsers" -> document.remove("users");
            case "nullLink" -> document.withArray("links").addNull();
            case "unknownField" -> document.put("extra", true);
            case "unknownRecipient" ->
                    document.withArray("notifications")
                            .addObject()
                            .put("ownerId", OTHER_OWNER)
                            .put("shortLink", "https://short.local/Abc12345")
                            .put("message", "Срок действия истёк.");
            default -> throw new AssertionError("Неизвестный сценарий: " + problem);
        }
        Path path = directory.resolve("storage.json");
        String contents = document.toString();
        Files.writeString(path, contents);

        assertThrows(StorageException.class, () -> new JsonLinkRepository(path));

        assertEquals(contents, Files.readString(path));
    }

    @Test
    void reportsDirectoryInsteadOfStorageFile() {
        assertThrows(StorageException.class, () -> new JsonLinkRepository(directory));
    }

    @Test
    void cleansTemporaryFileWhenAtomicReplacementFails() throws Exception {
        Path target = directory.resolve("storage.json");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "Сохранить");
        JsonStorageFile file = new JsonStorageFile(target);

        assertThrows(StorageException.class, () -> file.save(StorageSnapshot.empty()));

        assertEquals("Сохранить", Files.readString(target.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
