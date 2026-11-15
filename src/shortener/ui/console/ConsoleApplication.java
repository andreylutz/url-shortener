package shortener.ui.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import shortener.core.port.StorageException;

public final class ConsoleApplication {
    private final BufferedReader input;
    private final ConsoleCommands commands;
    private final ConsoleView view;

    public ConsoleApplication(Reader input, ConsoleCommands commands, ConsoleView view) {
        this.input = new BufferedReader(input);
        this.commands = commands;
        this.view = view;
    }

    public void run() throws IOException {
        view.welcome();
        while (true) {
            view.prompt();
            String line = input.readLine();
            if (line == null) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                if (!commands.execute(line)) {
                    break;
                }
            } catch (StorageException exception) {
                throw exception;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                view.error(exception.getMessage());
            }
        }
        view.message("Работа завершена.");
    }
}
