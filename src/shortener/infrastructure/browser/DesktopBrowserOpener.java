package shortener.infrastructure.browser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import shortener.core.port.BrowserException;
import shortener.core.port.BrowserOpener;

public final class DesktopBrowserOpener implements BrowserOpener {
    @Override
    public void open(URI uri) throws BrowserException {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new BrowserException("Java Desktop не поддерживается в этом окружении.");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new BrowserException("Действие BROWSE не поддерживается в этом окружении.");
            }
            desktop.browse(uri);
        } catch (IOException exception) {
            throw new BrowserException(
                    "Системный вызов открытия браузера завершился ошибкой.", exception);
        } catch (UnsupportedOperationException exception) {
            throw new BrowserException("Открытие браузера недоступно в этом окружении.", exception);
        } catch (SecurityException exception) {
            throw new BrowserException(
                    "Открытие браузера запрещено настройками безопасности.", exception);
        }
    }
}
