package shortener.ui.console;

import java.util.Arrays;
import java.util.UUID;
import shortener.core.model.ShortLink;
import shortener.core.service.LinkManagementService;
import shortener.core.service.LinkOpeningService;
import shortener.core.service.ShortLinkService;

public final class ConsoleCommands {
    private final ShortLinkService users;
    private final LinkManagementService management;
    private final LinkOpeningService opening;
    private final ConsoleView view;

    public ConsoleCommands(
            ShortLinkService users,
            LinkManagementService management,
            LinkOpeningService opening,
            ConsoleView view) {
        this.users = users;
        this.management = management;
        this.opening = opening;
        this.view = view;
    }

    public boolean execute(String line) {
        String[] tokens = line.strip().split("\\s+");
        String command = tokens[0];
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        if (!command.equals("notifications")) {
            showPendingNotifications();
        }
        switch (command) {
            case "create" -> create(args);
            case "user" -> selectUser(args);
            case "list" -> {
                requireCount(args, 0, "list");
                view.links(management.listOwnLinks());
            }
            case "show" -> {
                requireCount(args, 1, "show <link>");
                view.details(management.getOwnLink(args[0]));
            }
            case "open" -> {
                requireCount(args, 1, "open <link>");
                view.opened(opening.open(args[0]));
            }
            case "set-limit" -> changeLimit(args);
            case "set-url" -> changeUrl(args);
            case "delete" -> {
                requireCount(args, 1, "delete <link>");
                management.deleteOwnLink(args[0]);
                view.message("Ссылка удалена: " + args[0]);
            }
            case "notifications" -> {
                requireCount(args, 0, "notifications");
                view.notifications(users.readNotifications(), true);
            }
            case "help" -> {
                requireCount(args, 0, "help");
                view.help();
            }
            case "exit" -> {
                requireCount(args, 0, "exit");
                return false;
            }
            default ->
                    throw new IllegalArgumentException(
                            "Неизвестная команда '" + command + "'. Введите help для справки.");
        }
        return true;
    }

    private void create(String[] args) {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "Использование: create <URL> [limit]. Пример: create https://example.org 5");
        }
        boolean newUser = users.currentUserId().isEmpty();
        Integer limit = args.length == 2 ? parseLimit(args[1]) : null;
        ShortLink link = users.create(args[0], limit);
        if (newUser) {
            view.newUser(link.ownerId());
        }
        view.created(link);
    }

    private void selectUser(String[] args) {
        requireCount(args, 1, "user new | user <UUID>");
        if (args[0].equals("new")) {
            users.startNewUser();
            view.message("Режим нового пользователя. UUID будет выдан после создания ссылки.");
        } else {
            UUID userId = users.selectUser(args[0]);
            view.message("Выбран пользователь: " + userId);
            showPendingNotifications();
        }
    }

    private void changeLimit(String[] args) {
        requireCount(args, 2, "set-limit <link> <limit>");
        ShortLink link = management.changeVisitLimit(args[0], parseLimit(args[1]));
        view.message(
                "Лимит ссылки "
                        + args[0]
                        + " изменён: "
                        + link.visitLimit()
                        + ". Использовано переходов: "
                        + link.usedCount()
                        + ".");
    }

    private void changeUrl(String[] args) {
        requireCount(args, 2, "set-url <link> <URL>");
        ShortLink link = management.changeOriginalUrl(args[0], args[1]);
        view.message("Исходный URL ссылки " + args[0] + " изменён: " + link.originalUri());
    }

    private void showPendingNotifications() {
        if (users.currentUserId().isPresent()) {
            view.notifications(users.readNotifications(), false);
        }
    }

    private int parseLimit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Лимит должен быть целым числом от 1 до " + Integer.MAX_VALUE + ".", exception);
        }
    }

    private void requireCount(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new IllegalArgumentException("Использование: " + usage);
        }
    }
}
