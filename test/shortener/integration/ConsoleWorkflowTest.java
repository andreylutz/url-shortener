package shortener.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shortener.core.model.ShortLink;
import shortener.core.port.LinkRepository;
import shortener.core.port.StorageException;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.storage.JsonLinkRepository;
import shortener.ui.console.ConsoleApplication;
import shortener.ui.console.ConsoleCommands;
import shortener.ui.console.ConsoleView;

class ConsoleWorkflowTest {
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String PREFIX = "https://short.local/";
    private static final UUID FIRST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir Path directory;
    private final List<URI> opened = new ArrayList<>();

    @Test
    void completesTwoUserWorkflowAndRestoresItsResultsFromJson() throws IOException {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        repository.add(link("Owner001", FIRST_OWNER));
        repository.add(link("Owner002", SECOND_OWNER));
        StringWriter output = new StringWriter();

        application(
                        repository,
                        """
                user 00000000-0000-0000-0000-000000000001
                create https://example.org/Same
                list
                open https://short.local/Owner001
                notifications
                user 00000000-0000-0000-0000-000000000002
                show https://short.local/Owner001
                set-limit https://short.local/Owner001 4
                set-url https://short.local/Owner001 https://example.org/Forbidden
                delete https://short.local/Owner001
                delete https://short.local/Owner002
                create https://example.org/Same
                list
                user 00000000-0000-0000-0000-000000000001
                set-limit https://short.local/Owner001 3
                set-url https://short.local/Owner001 https://example.org/New?Token=AbC
                open https://short.local/Owner001
                show https://short.local/Owner001
                help
                exit
                """,
                        output)
                .run();

        JsonLinkRepository restored = new JsonLinkRepository(path);
        ShortLink first = restored.findByCode("Owner001").orElseThrow();
        assertEquals(FIRST_OWNER, first.ownerId());
        assertEquals(2, first.usedCount());
        assertEquals(3, first.visitLimit());
        assertEquals(URI.create("https://example.org/New?Token=AbC"), first.originalUri());
        assertEquals(NOW.plus(Duration.ofHours(1)), first.expiresAt());
        assertTrue(restored.findByCode("Owner002").isEmpty());
        assertEquals(2, restored.findByOwner(FIRST_OWNER).size());
        assertEquals(1, restored.findByOwner(SECOND_OWNER).size());
        ShortLink sameUrlFirst =
                restored.findByOwner(FIRST_OWNER).stream()
                        .filter(link -> !link.code().equals("Owner001"))
                        .findFirst()
                        .orElseThrow();
        ShortLink sameUrlSecond = restored.findByOwner(SECOND_OWNER).getFirst();
        assertEquals(sameUrlFirst.originalUri(), sameUrlSecond.originalUri());
        assertFalse(sameUrlFirst.code().equals(sameUrlSecond.code()));
        assertEquals(List.of(URI.create("https://example.org/Same"), first.originalUri()), opened);
        assertTrue(restored.drainNotifications(FIRST_OWNER).isEmpty());
        assertEquals(4, output.toString().split("принадлежит другому пользователю", -1).length - 1);
        assertTrue(output.toString().contains("Переходы: 2/3"));
        assertTrue(output.toString().contains("Команды:"));
        assertTrue(output.toString().contains("Работа завершена."));
    }

    @Test
    void preservesUnreadNotificationAtEndOfInputAndDeliversOnNextUserSelection()
            throws IOException {
        Path path = directory.resolve("storage.json");
        JsonLinkRepository repository = new JsonLinkRepository(path);
        repository.add(link("Owner001", FIRST_OWNER));

        application(
                        repository,
                        "user " + FIRST_OWNER + "\nopen " + PREFIX + "Owner001\n",
                        new StringWriter())
                .run();
        StringWriter output = new StringWriter();
        application(
                        new JsonLinkRepository(path),
                        "user " + FIRST_OWNER + "\nnotifications\nexit\n",
                        output)
                .run();

        assertEquals(1, output.toString().split("Уведомление:", -1).length - 1);
        assertTrue(output.toString().contains("достигнут лимит"));
        assertTrue(output.toString().contains("Новых уведомлений нет."));
        assertTrue(new JsonLinkRepository(path).drainNotifications(FIRST_OWNER).isEmpty());
    }

    @Test
    void storageFailureStopsConsoleWithoutPrintingSuccessfulCreation() throws IOException {
        Path blocker = directory.resolve("blocked");
        JsonLinkRepository repository = new JsonLinkRepository(blocker.resolve("storage.json"));
        Files.writeString(blocker, "Препятствие для создания каталога данных.");
        StringWriter output = new StringWriter();
        ConsoleApplication console =
                application(repository, "create https://example.org\nhelp\nexit\n", output);

        StorageException failure = assertThrows(StorageException.class, console::run);

        assertTrue(failure.getMessage().contains("сохранить"));
        assertFalse(output.toString().contains("Короткая ссылка:"));
        assertFalse(output.toString().contains("UUID пользователя:"));
        assertFalse(output.toString().contains("Команды:"));
        assertFalse(output.toString().contains("Работа завершена."));
        assertEquals("Препятствие для создания каталога данных.", Files.readString(blocker));
        assertThrows(StorageException.class, repository::findAll);
    }

    private ConsoleApplication application(
            LinkRepository repository, String script, StringWriter output) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UserSession session = new UserSession();
        ShortLinkService users =
                new ShortLinkService(
                        repository,
                        session,
                        new RandomCodeGenerator(8, new Random(42)),
                        clock,
                        5,
                        Duration.ofHours(1));
        LinkExpirationService expiration = new LinkExpirationService(repository, clock, PREFIX);
        LinkManagementService management =
                new LinkManagementService(repository, session, expiration, PREFIX, 8);
        LinkOpeningService opening =
                new LinkOpeningService(repository, opened::add, expiration, PREFIX, 8);
        ConsoleView view = new ConsoleView(new PrintWriter(output), PREFIX, 8, clock);
        ConsoleCommands commands = new ConsoleCommands(users, management, opening, view);
        return new ConsoleApplication(new StringReader(script), commands, view);
    }

    private ShortLink link(String code, UUID ownerId) {
        return new ShortLink(
                code,
                ownerId,
                URI.create("https://example.org/Same"),
                1,
                0,
                NOW,
                NOW.plus(Duration.ofHours(1)));
    }
}
