package shortener.core.port;

public final class BrowserException extends Exception {
    public BrowserException(String message) {
        super(message);
    }

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
