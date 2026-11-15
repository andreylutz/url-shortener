package shortener.infrastructure.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import shortener.core.port.StorageException;

final class JsonStorageFile {
    private final Path path;
    private final ObjectMapper mapper =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    JsonStorageFile(Path path) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    StorageSnapshot load() {
        try (InputStream input = Files.newInputStream(path)) {
            StorageSnapshot snapshot = mapper.readValue(input, StorageSnapshot.class);
            if (snapshot == null) {
                throw new StorageException(
                        "Файл данных '" + path + "' содержит null вместо хранилища.");
            }
            return snapshot;
        } catch (NoSuchFileException exception) {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return StorageSnapshot.empty();
            }
            throw new StorageException(
                    "Не удалось открыть существующий файл данных '" + path + "'.", exception);
        } catch (JsonProcessingException exception) {
            String reason = "некорректный JSON или структура данных";
            if (exception.getCause() instanceof IllegalArgumentException cause) {
                reason = cause.getMessage();
            }
            throw new StorageException("Файл данных '" + path + "': " + reason + ".", exception);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            throw new StorageException(
                    "Не удалось прочитать файл данных '" + path + "'.", exception);
        }
    }

    void save(StorageSnapshot snapshot) {
        Path temporary = null;
        try {
            byte[] contents = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            Path directory = path.getParent();
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "shortener-", ".tmp");
            Files.write(temporary, contents);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            deleteTemporaryFile(temporary, exception);
            String reason =
                    exception instanceof AtomicMoveNotSupportedException
                            ? "файловая система не поддерживает атомарную замену"
                            : "не удалось записать или заменить файл";
            throw new StorageException(
                    "Не удалось сохранить данные в '" + path + "': " + reason + ".", exception);
        }
    }

    private void deleteTemporaryFile(Path temporary, Exception failure) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            failure.addSuppressed(exception);
        }
    }
}
