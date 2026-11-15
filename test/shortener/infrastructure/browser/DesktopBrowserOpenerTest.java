package shortener.infrastructure.browser;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import shortener.core.port.BrowserException;

class DesktopBrowserOpenerTest {
    @Test
    @EnabledIfSystemProperty(named = "java.awt.headless", matches = "true")
    void reportsUnsupportedDesktopInHeadlessEnvironment() {
        DesktopBrowserOpener browser = new DesktopBrowserOpener();

        BrowserException exception =
                assertThrows(
                        BrowserException.class,
                        () -> browser.open(URI.create("https://example.org")));

        assertTrue(exception.getMessage().contains("Desktop"));
    }
}
