package shortener.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import shortener.core.model.Notification;
import shortener.core.model.ShortLink;

public interface LinkRepository {
    /**
     * Исключает одновременное выполнение составных операций над ссылками и уведомлениями.
     * Изменения, выполненные до исключения, сохраняются: отказ по TTL может создавать уведомление.
     */
    <T> T executeExclusively(Supplier<T> operation);

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

    List<ShortLink> findAll();

    boolean remove(String code);

    boolean userExists(UUID userId);

    void addNotification(Notification notification);

    List<Notification> drainNotifications(UUID ownerId);
}
