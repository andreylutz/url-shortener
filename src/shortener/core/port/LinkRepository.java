package shortener.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import shortener.core.model.ShortLink;

public interface LinkRepository {
    /**
     * Добавляет ссылку и регистрирует её владельца одной операцией. Занятый код возвращает false
     * без изменения хранилища.
     */
    boolean add(ShortLink link);

    Optional<ShortLink> findByCode(String code);

    List<ShortLink> findByOwner(UUID ownerId);

    boolean userExists(UUID userId);
}
