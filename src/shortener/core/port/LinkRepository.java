package shortener.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import shortener.core.model.ShortLink;

public interface LinkRepository {
    /**
     * Добавляет ссылку и регистрирует её владельца одной операцией. Занятый код возвращает false
     * без изменения хранилища.
     */
    boolean add(ShortLink link);

    Optional<ShortLink> findByCode(String code);

    /**
     * Выполняет операцию и заменяет запись под общей блокировкой. Код и владелец не меняются. При
     * исключении запись сохраняется без изменений. Отсутствующий код возвращает пустой результат
     * без вызова операции.
     */
    Optional<ShortLink> update(String code, UnaryOperator<ShortLink> operation);

    List<ShortLink> findByOwner(UUID ownerId);

    boolean userExists(UUID userId);
}
