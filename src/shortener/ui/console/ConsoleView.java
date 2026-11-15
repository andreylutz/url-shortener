package shortener.ui.console;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;

public final class ConsoleView {
    private final PrintWriter output;
    private final String shortLinkPrefix;
    private final String exampleLink;
    private final Clock clock;

    public ConsoleView(PrintWriter output, String shortLinkPrefix, int codeLength, Clock clock) {
        this.output = output;
        this.shortLinkPrefix = shortLinkPrefix;
        this.exampleLink = shortLinkPrefix + "A".repeat(codeLength);
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

    public void newUserSession() {
        message("Режим нового пользователя. UUID будет выдан после создания ссылки.");
    }

    public void userSelected(UUID userId) {
        message("Выбран пользователь: " + userId);
    }

    public void created(ShortLink link) {
        message("Короткая ссылка: " + formatShortLink(link));
        message("Лимит: " + link.visitLimit() + ". Действует до (UTC): " + link.expiresAt());
    }

    public void opened(ShortLink link) {
        message("Адрес передан браузеру: " + link.originalUri());
        message(
                "Ссылка %s: использовано переходов %s/%s."
                        .formatted(formatShortLink(link), link.usedCount(), link.visitLimit()));
    }

    public void limitChanged(ShortLink link) {
        message(
                "Лимит ссылки %s изменён: %s. Использовано переходов: %s."
                        .formatted(formatShortLink(link), link.visitLimit(), link.usedCount()));
    }

    public void originalUrlChanged(ShortLink link) {
        message(
                "Исходный URL ссылки %s изменён: %s"
                        .formatted(formatShortLink(link), link.originalUri()));
    }

    public void deleted(String shortLink) {
        message("Ссылка удалена: " + shortLink);
    }

    public void links(List<ShortLink> links) {
        if (links.isEmpty()) {
            message("У текущего пользователя нет ссылок.");
            return;
        }
        for (ShortLink link : links) {
            message(
                    "%s -> %s | переходы %s/%s | до %s (UTC) | %s"
                            .formatted(
                                    formatShortLink(link),
                                    link.originalUri(),
                                    link.usedCount(),
                                    link.visitLimit(),
                                    link.expiresAt(),
                                    status(link)));
        }
    }

    public void details(ShortLink link) {
        message("Короткая ссылка: " + formatShortLink(link));
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

    private String formatShortLink(ShortLink link) {
        return shortLinkPrefix + link.code();
    }

    private String status(ShortLink link) {
        if (link.isExpiredAt(clock.instant())) {
            return "срок истёк";
        }
        return link.usedCount() >= link.visitLimit() ? "лимит исчерпан" : "доступна";
    }
}
