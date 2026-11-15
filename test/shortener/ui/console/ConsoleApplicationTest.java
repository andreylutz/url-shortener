package shortener.ui.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;
import shortener.core.port.BrowserException;
import shortener.core.port.BrowserOpener;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.storage.InMemoryLinkRepository;
import shortener.support.MutableClock;

class ConsoleApplicationTest {
    private static final String PREFIX = "https://short.local/";
    private static final String FIRST_LINK = PREFIX + "Code0001";
    private static final String SECOND_LINK = PREFIX + "Code0002";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final UserSession session = new UserSession();
    private final AtomicInteger sequence = new AtomicInteger();
    private final ShortLinkService users =
            new ShortLinkService(
                    repository,
                    session,
                    () -> "Code%04d".formatted(sequence.incrementAndGet()),
                    clock,
                    3,
                    Duration.ofHours(1));
    private final LinkExpirationService expiration =
            new LinkExpirationService(repository, clock, PREFIX);
    private final LinkManagementService management =
            new LinkManagementService(repository, session, expiration, PREFIX, 8);
    private final List<URI> opened = new ArrayList<>();

    @Test
    void createsSeveralLinksAndDisplaysNewUserUuidOnlyOnce() throws IOException {
        String output =
                run(
                        """
                create https://example.org/Article?Token=AbC 2
                create https://example.org/Other
                list
                show https://short.local/Code0001
                exit
                """);

        assertEquals(2, repository.findAll().size());
        assertEquals(
                repository.findByCode("Code0001").orElseThrow().ownerId(),
                repository.findByCode("Code0002").orElseThrow().ownerId());
        assertEquals(1, occurrences(output, "UUID пользователя:"));
        assertTrue(output.contains("user " + users.currentUserId().orElseThrow()));
        assertTrue(output.contains("https://example.org/Article?Token=AbC"));
        assertTrue(output.contains("Переходы: 0/2"));
        assertTrue(output.contains("Создана (UTC): " + NOW));
        assertTrue(output.contains("Истекает (UTC): " + NOW.plus(Duration.ofHours(1))));
        assertTrue(output.contains(SECOND_LINK));
        assertEquals(3, repository.findByCode("Code0002").orElseThrow().visitLimit());
    }

    @Test
    void editsExhaustedLinkWithoutResettingCountAndDeletesIt() throws IOException {
        String output =
                run(
                        """
                create https://example.org/Old 1
                open https://short.local/Code0001
                open https://short.local/Code0001
                set-limit https://short.local/Code0001 1
                set-limit https://short.local/Code0001 3
                set-url https://short.local/Code0001 https://example.org/New?Token=AbC
                open https://short.local/Code0001
                show https://short.local/Code0001
                delete https://short.local/Code0001
                show https://short.local/Code0001
                exit
                """);

        assertEquals(
                List.of(
                        URI.create("https://example.org/Old"),
                        URI.create("https://example.org/New?Token=AbC")),
                opened);
        assertTrue(output.contains("лимит переходов исчерпан"));
        assertTrue(output.contains("Переходы: 2/3"));
        assertTrue(output.contains("Ссылка удалена: " + FIRST_LINK));
        assertTrue(output.contains(FIRST_LINK + " не найдена"));
        assertTrue(repository.findAll().isEmpty());
        assertTrue(repository.userExists(users.currentUserId().orElseThrow()));
    }

    @Test
    void switchesUsersAndRestrictsListsDetailsAndMutationsToOwner() throws IOException {
        ShortLink first = users.create("https://example.org/First");
        users.startNewUser();
        ShortLink second = users.create("https://example.org/Second");

        String otherUserOutput =
                run(
                        "list\nshow "
                                + FIRST_LINK
                                + "\nset-limit "
                                + FIRST_LINK
                                + " 5\nset-url "
                                + FIRST_LINK
                                + " https://example.org/Forbidden\ndelete "
                                + FIRST_LINK
                                + "\nexit\n");

        assertTrue(otherUserOutput.contains("https://example.org/Second"));
        assertFalse(otherUserOutput.contains("https://example.org/First"));
        assertEquals(4, occurrences(otherUserOutput, "принадлежит другому пользователю"));
        assertEquals(first, repository.findByCode(first.code()).orElseThrow());

        String ownerOutput = run("user " + first.ownerId() + "\nlist\nexit\n");
        assertTrue(ownerOutput.contains("https://example.org/First"));
        assertFalse(ownerOutput.contains("https://example.org/Second"));
        assertEquals(first.ownerId(), users.currentUserId().orElseThrow());
        assertEquals(second, repository.findByCode(second.code()).orElseThrow());
    }

    @Test
    void startsNewUserOnlyAfterSuccessfulCreation() throws IOException {
        ShortLink first = users.create("https://example.org/First");

        String output =
                run(
                        """
                user new
                list
                create invalid
                create https://example.org/Second 0
                create https://example.org/Second
                exit
                """);

        ShortLink second = repository.findByCode("Code0002").orElseThrow();
        assertFalse(first.ownerId().equals(second.ownerId()));
        assertEquals(1, occurrences(output, "UUID пользователя:"));
        assertEquals(2, repository.findAll().size());
        assertTrue(output.contains("Сначала выберите пользователя"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unknown",
                "create",
                "create https://example.org 1 extra",
                "create https://example.org abc",
                "create https://example.org 2147483648",
                "create ftp://example.org",
                "user",
                "user invalid",
                "user 00000000-0000-0000-0000-000000000001",
                "list extra",
                "show",
                "open invalid",
                "set-limit",
                "set-url",
                "delete",
                "notifications extra",
                "help extra",
                "exit extra"
            })
    void invalidInputDoesNotEndSessionOrCreateUser(String command) throws IOException {
        String output = run(command + "\nhelp\nexit\n");

        assertTrue(output.contains("Ошибка:"));
        assertTrue(output.contains("Команды:"));
        assertTrue(output.contains("Работа завершена."));
        assertTrue(repository.findAll().isEmpty());
        assertTrue(users.currentUserId().isEmpty());
        assertFalse(output.contains("Exception"));
    }

    @Test
    void browserFailureDoesNotConsumeVisitOrEndSession() throws IOException {
        BrowserOpener unavailableBrowser =
                uri -> {
                    throw new BrowserException("Браузер недоступен.");
                };
        String output =
                run(
                        new StringReader(
                                "create https://example.org 1\nopen "
                                        + FIRST_LINK
                                        + "\nshow "
                                        + FIRST_LINK
                                        + "\nexit\n"),
                        unavailableBrowser);

        assertTrue(output.contains("Браузер недоступен."));
        assertTrue(output.contains("Переход не израсходован"));
        assertTrue(output.contains("Переходы: 0/1"));
        assertEquals(0, repository.findByCode("Code0001").orElseThrow().usedCount());
        assertTrue(users.readNotifications().isEmpty());
    }

    @Test
    void allowsOpeningWithoutSelectedUserAndDoesNotRevealOwnerUuid() throws IOException {
        ShortLink link = users.create("https://example.org");
        users.startNewUser();

        String output = run("open " + FIRST_LINK + "\nexit\n");

        assertEquals(List.of(link.originalUri()), opened);
        assertFalse(output.contains(link.ownerId().toString()));
        assertTrue(users.currentUserId().isEmpty());
    }

    @Test
    void displaysLimitNotificationOnceOnNextCommand() throws IOException {
        String output =
                run(
                        "create https://example.org 1\nopen "
                                + FIRST_LINK
                                + "\nhelp\nnotifications\nhelp\nexit\n");

        assertEquals(1, occurrences(output, "Уведомление:"));
        assertTrue(output.indexOf("Адрес передан браузеру:") < output.indexOf("Уведомление:"));
        assertTrue(output.indexOf("Уведомление:") < output.indexOf("Команды:"));
        assertTrue(output.contains("Новых уведомлений нет."));
    }

    @Test
    void explicitNotificationsDoesNotReportEmptyQueueAfterPrintingPendingNotification()
            throws IOException {
        String output =
                run("create https://example.org 1\nopen " + FIRST_LINK + "\nnotifications\nexit\n");

        assertEquals(1, occurrences(output, "Уведомление:"));
        assertFalse(output.contains("Новых уведомлений нет."));
    }

    @Test
    void keepsOtherOwnersNotificationsUntilTheirSelection() throws IOException {
        ShortLink first = users.create("https://example.org/First", 1);
        users.startNewUser();
        users.create("https://example.org/Second");
        repository.addNotification(Notification.limitReached(first, PREFIX));

        String beforeSelection = run("notifications\nexit\n");
        assertFalse(beforeSelection.contains("Уведомление:"));

        String selected = run("user " + first.ownerId() + "\nhelp\nexit\n");
        assertEquals(1, occurrences(selected, "Уведомление:"));
        assertTrue(selected.contains(FIRST_LINK));
        assertTrue(users.readNotifications().isEmpty());
    }

    @Test
    void blocksExpiredOpeningAndDeliversReasonAfterPhysicalDeletion() throws IOException {
        ShortLink link = users.create("https://example.org");
        clock.set(link.expiresAt());

        String blocked = run("show " + FIRST_LINK + "\nopen " + FIRST_LINK + "\nexit\n");
        assertTrue(blocked.contains("Статус: срок истёк"));
        assertTrue(blocked.contains("заблокирован: срок действия истёк"));
        assertTrue(opened.isEmpty());
        assertEquals(1, occurrences(blocked, "Уведомление:"));
        assertEquals(1, expiration.cleanup());
        assertTrue(users.readNotifications().isEmpty());

        users.create("https://example.org/Next");
        clock.set(clock.instant().plus(Duration.ofHours(1)));
        assertEquals(1, expiration.cleanup());
        String deleted = run("notifications\nshow " + SECOND_LINK + "\nexit\n");
        assertTrue(deleted.contains("Срок действия ссылки " + SECOND_LINK + " истёк"));
        assertTrue(deleted.contains(SECOND_LINK + " не найдена"));
        assertEquals(1, occurrences(deleted, "Уведомление:"));
    }

    @Test
    void handlesWhitespaceAndStopsAtExitWithoutExecutingRemainingInput() throws IOException {
        String output =
                run(
                        " \n\t\n  create\thttps://example.org/A%20B?Token=AbC   2  \n exit \ncreate https://ignored.org\n");

        assertEquals(1, repository.findAll().size());
        assertEquals(
                URI.create("https://example.org/A%20B?Token=AbC"),
                repository.findAll().getFirst().originalUri());
        assertFalse(output.contains("Ошибка:"));
        assertTrue(output.contains("Работа завершена."));
    }

    @Test
    void endsNormallyOnEndOfInput() throws IOException {
        String output = run("create https://example.org");

        assertEquals(1, repository.findAll().size());
        assertTrue(output.contains("Работа завершена."));
        assertFalse(output.contains("Ошибка:"));
    }

    @Test
    void propagatesInputFailureInsteadOfReportingNormalExit() {
        Reader brokenInput =
                new Reader() {
                    @Override
                    public int read(char[] buffer, int offset, int length) throws IOException {
                        throw new IOException("Не удалось прочитать ввод.");
                    }

                    @Override
                    public void close() {}
                };

        IOException failure = assertThrows(IOException.class, () -> run(brokenInput, opened::add));
        assertEquals("Не удалось прочитать ввод.", failure.getMessage());
        assertTrue(repository.findAll().isEmpty());
    }

    private String run(String script) throws IOException {
        return run(new StringReader(script), opened::add);
    }

    private String run(Reader input, BrowserOpener browser) throws IOException {
        StringWriter output = new StringWriter();
        ConsoleView view = new ConsoleView(new PrintWriter(output), PREFIX, 8, clock);
        LinkOpeningService opening =
                new LinkOpeningService(repository, browser, expiration, PREFIX, 8);
        ConsoleCommands commands = new ConsoleCommands(users, management, opening, view);
        new ConsoleApplication(input, commands, view).run();
        return output.toString();
    }

    private int occurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }
}
