package shortener.infrastructure.generation;

import java.util.Objects;
import java.util.random.RandomGenerator;
import shortener.core.port.CodeGenerator;

public final class RandomCodeGenerator implements CodeGenerator {
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final int codeLength;
    private final RandomGenerator random;

    public RandomCodeGenerator(int codeLength, RandomGenerator random) {
        if (codeLength <= 0) {
            throw new IllegalArgumentException("Длина короткого кода должна быть положительной.");
        }
        this.codeLength = codeLength;
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public String generate() {
        StringBuilder code = new StringBuilder(codeLength);
        for (int index = 0; index < codeLength; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
