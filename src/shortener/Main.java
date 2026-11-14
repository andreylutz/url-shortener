package shortener;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import shortener.infrastructure.config.ConfigLoader;
import shortener.infrastructure.config.ConfigurationException;

public final class Main {
    private static final Path DEFAULT_CONFIG_PATH = Path.of("config/application.properties");

    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = resolveConfigPath(args);
            new ConfigLoader().load(configPath);
            System.out.println("Настройки приложения загружены из " + configPath + ".");
        } catch (ConfigurationException exception) {
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
