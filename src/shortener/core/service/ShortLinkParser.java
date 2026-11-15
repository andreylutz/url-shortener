package shortener.core.service;

final class ShortLinkParser {
    private final String prefix;
    private final int codeLength;

    ShortLinkParser(String prefix, int codeLength) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Префикс коротких ссылок не должен быть пустым.");
        }
        if (codeLength <= 0) {
            throw new IllegalArgumentException("Длина короткого кода должна быть положительной.");
        }
        this.prefix = prefix;
        this.codeLength = codeLength;
    }

    String parseCode(String shortLink) {
        if (shortLink == null || shortLink.isBlank()) {
            throw new IllegalArgumentException("Укажите короткую ссылку.");
        }
        if (!shortLink.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "Некорректная короткая ссылка " + shortLink + ": ожидается префикс " + prefix);
        }
        String code = shortLink.substring(prefix.length());
        if (code.length() != codeLength || !code.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Некорректная короткая ссылка "
                            + shortLink
                            + ": код должен содержать "
                            + codeLength
                            + " латинских букв или цифр.");
        }
        return code;
    }
}
