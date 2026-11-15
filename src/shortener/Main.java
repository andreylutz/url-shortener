package shortener;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import shortener.core.service.LinkExpirationService;
import shortener.infrastructure.config.AppConfig;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.config.ConfigurationException;
import shortener.infrastructure.scheduling.PeriodicCleanup;
import shortener.infrastructure.storage.JsonLinkRepository;

public final class Main {
    private static final Path DEFAULT_CONFIG_PATH = Path.of("config/application.properties");

    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = resolveConfigPath(args);
            AppConfig config = new ConfigLoader().load(configPath);
            JsonLinkRepository repository = new JsonLinkRepository(config.storagePath());
            LinkExpirationService expiration =
                    new LinkExpirationService(
                            repository, Clock.systemUTC(), config.shortLinkPrefix());
            try (PeriodicCleanup cleanup =
                    new PeriodicCleanup(
                            expiration::cleanup,
                            config.cleanupInterval(),
                            Executors.newSingleThreadScheduledExecutor(),
                            exception ->
                                    System.err.println(
                                            "Ошибка фоновой очистки: " + exception.getMessage()))) {
                cleanup.start();
                System.out.println("Настройки приложения загружены из " + configPath + ".");
            }
        } catch (ConfigurationException | IllegalStateException exception) {
            System.err.println("Ошибка запуска: " + exception.getMessage());
            System.exit(1);
        }
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
