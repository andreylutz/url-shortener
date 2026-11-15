package shortener;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.Executors;
import shortener.core.port.LinkRepository;
import shortener.core.service.LinkExpirationService;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;
import shortener.core.service.UserSession;
import shortener.infrastructure.browser.DesktopBrowserOpener;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.config.ConfigurationException;
import shortener.infrastructure.generation.RandomCodeGenerator;
import shortener.infrastructure.scheduling.PeriodicCleanup;
import shortener.infrastructure.storage.JsonLinkRepository;
import shortener.ui.console.ConsoleApplication;
import shortener.ui.console.ConsoleCommands;
import shortener.ui.console.ConsoleView;

public final class Main {
    private static final Path DEFAULT_CONFIG_PATH = Path.of("config/application.properties");

    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = resolveConfigPath(args);
            AppConfig config = new ConfigLoader().load(configPath);
            JsonLinkRepository repository = new JsonLinkRepository(config.storagePath());
            Clock clock = Clock.systemUTC();
            LinkExpirationService expiration =
                    new LinkExpirationService(repository, clock, config.shortLinkPrefix());
            try (PeriodicCleanup cleanup =
                    new PeriodicCleanup(
                            expiration::cleanup,
                            config.cleanupInterval(),
                            Executors.newSingleThreadScheduledExecutor(),
                            exception ->
                                    System.err.println(
                                            "Ошибка фоновой очистки: " + exception.getMessage()))) {
                cleanup.start();
                createConsole(config, repository, expiration, clock).run();
            }
        } catch (ConfigurationException | IllegalStateException | IOException exception) {
            System.err.println("Ошибка приложения: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static ConsoleApplication createConsole(
            AppConfig config,
            LinkRepository repository,
            LinkExpirationService expiration,
            Clock clock) {
        UserSession session = new UserSession();
        ShortLinkService shortLinks =
                new ShortLinkService(
                        repository,
                        session,
                        new RandomCodeGenerator(config.codeLength(), new SecureRandom()),
                        clock,
                        config.defaultVisitLimit(),
                        config.linkTtl());
        LinkManagementService management =
                new LinkManagementService(
                        repository,
                        session,
                        expiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        LinkOpeningService opening =
                new LinkOpeningService(
                        repository,
                        new DesktopBrowserOpener(),
                        expiration,
                        config.shortLinkPrefix(),
                        config.codeLength());
        ConsoleView view =
                new ConsoleView(
                        new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                        config.shortLinkPrefix(),
                        config.codeLength(),
                        clock);
        ConsoleCommands commands = new ConsoleCommands(shortLinks, management, opening, view);
        return new ConsoleApplication(
                new InputStreamReader(System.in, StandardCharsets.UTF_8), commands, view);
    }

    private static Path resolveConfigPath(String[] args) {
        if (args.length == 0) {
            return DEFAULT_CONFIG_PATH;
        }
        if (args.length == 2 && args[0].equals("--config") && !args[1].isBlank()) {
            try {
                return Path.of(args[1]);
            } catch (InvalidPathException exception) {
                throw new ConfigurationException(
                        "Параметр '--config': некорректный путь к файлу.", exception);
            }
        }
        throw new ConfigurationException(
                "Использование: java -jar target/url-shortener.jar [--config <путь>].");
    }
}
