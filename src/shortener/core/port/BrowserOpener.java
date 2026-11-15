package shortener.core.port;

import java.net.URI;

@FunctionalInterface
public interface BrowserOpener {
    /** Успешный возврат подтверждает системный вызов открытия, но не загрузку страницы. */
    void open(URI uri) throws BrowserException;
}
