package shortener.infrastructure.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RandomCodeGeneratorTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 8, 64})
    void generatesBase62CodeWithConfiguredLength(int length) {
        RandomCodeGenerator generator = new RandomCodeGenerator(length, new Random(42));

        String code = generator.generate();

        assertEquals(length, code.length());
        assertTrue(code.matches("[A-Za-z0-9]+"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveCodeLength(int length) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RandomCodeGenerator(length, new Random(42)));
    }
}
