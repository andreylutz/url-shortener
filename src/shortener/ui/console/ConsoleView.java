package shortener.ui.console;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;

public final class ConsoleView {
    private final PrintWriter output;
    private final String prefix;
    private final String exampleLink;
    private final Clock clock;

    public ConsoleView(PrintWriter output, String prefix, int codeLength, Clock clock) {
        this.output = output;
        this.prefix = prefix;
        this.exampleLink = prefix + "A".repeat(codeLength);
        this.clock = clock;
    }

    public void welcome() {
        message("Сервис сокращения ссылок. Введите help для справки.");
        message("Создайте ссылку командой create или выберите пользователя: user <UUID>.");
    }

    public void prompt() {
        output.print("> ");
        output.flush();
    }

    public void message(String message) {
        output.println(message);
        output.flush();
    }

    public void error(String message) {
        message("Ошибка: " + message);
    }

    public void newUser(UUID userId) {
        message("UUID пользователя: " + userId);
        message("Сохраните UUID. Для последующей работы: user " + userId);
    }

    public void created(ShortLink link) {
        message("Короткая ссылка: " + prefix + link.code());
        message("Лимит: " + link.visitLimit() + ". Действует до (UTC): " + link.expiresAt());
    }

    public void opened(ShortLink link) {
        message("Адрес передан браузеру: " + link.originalUri());
        message(
                "Ссылка "
                        + prefix
                        + link.code()
                        + ": использовано переходов "
                        + link.usedCount()
                        + "/"
                        + link.visitLimit()
                        + ".");
    }

    public void links(List<ShortLink> links) {
        if (links.isEmpty()) {
            message("У текущего пользователя нет ссылок.");
            return;
        }
        for (ShortLink link : links) {
            message(
                    prefix
                            + link.code()
                            + " -> "
                            + link.originalUri()
                            + " | переходы "
                            + link.usedCount()
                            + "/"
                            + link.visitLimit()
                            + " | до "
                            + link.expiresAt()
                            + " (UTC) | "
                            + status(link));
        }
    }

    public void details(ShortLink link) {
        message("Короткая ссылка: " + prefix + link.code());
        message("Исходный URL: " + link.originalUri());
        message("UUID владельца: " + link.ownerId());
        message("Переходы: " + link.usedCount() + "/" + link.visitLimit());
        message("Создана (UTC): " + link.createdAt());
        message("Истекает (UTC): " + link.expiresAt());
        message("Статус: " + status(link));
    }

    public void notifications(List<Notification> notifications, boolean showEmpty) {
        if (notifications.isEmpty() && showEmpty) {
            message("Новых уведомлений нет.");
        }
        for (Notification notification : notifications) {
            message("Уведомление: " + notification.message());
        }
    }

    public void help() {
        message(
                """
                Команды:
                  create <URL> [limit]       Создать ссылку; без limit используется настройка.
                  user new                   Начать работу новым пользователем.
                  user <UUID>                Выбрать ранее созданного пользователя.
                  list                       Показать свои ссылки.
                  show <link>                Показать параметры своей ссылки.
                  open <link>                Открыть исходный адрес в браузере.
                  set-limit <link> <limit>    Изменить лимит своей ссылки.
                  set-url <link> <URL>        Изменить исходный URL своей ссылки.
                  delete <link>              Удалить свою ссылку.
                  notifications              Показать непрочитанные уведомления.
                  help                       Показать справку.
                  exit                       Завершить приложение.
                """);
        message("Примеры: create https://example.org/Article?lang=ru 5");
        message("         open " + exampleLink);
        message("         set-limit " + exampleLink + " 10");
        message("         set-url " + exampleLink + " https://example.org/New");
        message("Аргументы разделяются пробелами. Пробелы в URL кодируйте как %20.");
        message(
                "UUID выдаётся при первом успешном create. Пользователь не задаёт срок жизни ссылки.");
    }

    private String status(ShortLink link) {
        if (link.isExpiredAt(clock.instant())) {
            return "срок истёк";
        }
        return link.usedCount() >= link.visitLimit() ? "лимит исчерпан" : "доступна";
    }
}
