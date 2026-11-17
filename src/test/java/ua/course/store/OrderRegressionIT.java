package ua.course.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна робота №8 — Regression Testing and Flaky Test Debugging (виправлена версія).
 *
 * Групи (tags):
 *   "regression" — увесь набір (тег на рівні класу, тому його мають усі тести);
 *   "smoke"      — критичні сценарії; ці тести мають ОБИДВА теги, тому smoke є підмножиною regression.
 *
 * Запуск:  mvn -Psmoke test         (лише smoke)
 *          mvn -Pregression test    (повний regression, включає smoke)
 *
 * Порядок виконання навмисно ВИПАДКОВИЙ (MethodOrderer.Random) — він допомагає виявляти залежності
 * між тестами. Виправлені тести від порядку не залежать.
 *
 * Виправлені проблеми (симптом -> причина -> виправлення):
 *  1) returnOrder падав, якщо запускався окремо або раніше за createOrder
 *     -> залежав від static orderId, який виставляв інший тест -> тест сам створює замовлення.
 *  2) productStock / productRate: другий з них падав на UNIQUE(sku)
 *     -> обидва створювали товар зі спільним фіксованим sku "REGRESSION-EQUIPMENT" -> унікальний sku в кожному тесті.
 *  3) orderAfterPolicyCheck падав, коли зовнішня перевірка була повільнішою за 50 мс
 *     -> Thread.sleep(50) (fixed wait) замість очікування результату -> очікування завершення операції (future.get із таймаутом).
 *  4) orderSelection падав при іншому порядку вставки (-Dregression.reverseFixture=true)
 *     -> SQL без ORDER BY: порядок рядків не гарантований -> явний ORDER BY r.reference.
 */
@Tag("regression")
@TestMethodOrder(MethodOrderer.Random.class)
class OrderRegressionIT {
    // null, якщо тести працюють на локальній БД (запасний режим без Docker)
    static PostgreSQLContainer<?> postgres;

    static JdbcTemplate jdbc;
    static ProductRepository product;
    static OrderRepository orders;
    static OrderService service;

    @BeforeAll
    static void prepareDatabase() {
        String url;
        String user;
        String password;
        String localUrl = System.getProperty("regression.db.url", "");
        boolean local = !localUrl.isBlank();
        if (local) {
            // Запасний режим БЕЗ Docker: окрема локальна БД. Вона ПОВНІСТЮ очищається (Flyway clean), щоб стан
            // був таким самим, як у новому контейнері, тому її назва обов'язково містить "regression".
            if (!localUrl.contains("regression"))
                throw new IllegalStateException("Локальний режим дозволено лише для окремої БД, назва якої містить 'regression'");
            url = localUrl;
            user = System.getProperty("regression.db.user", "store_student");
            password = System.getProperty("regression.db.password", System.getenv("DB_PASSWORD"));
        } else {
            // Основний режим: PostgreSQL у контейнері (Testcontainers), як у вихідному наборі.
            postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        var flyway = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").executeInTransaction(false).cleanDisabled(!local).load();
        if (local) flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        product = new ProductRepository(jdbc);
        orders = new OrderRepository(jdbc);
        service = new OrderService(product, orders, (item, quantity) -> true);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Product item() throws Exception {
        return product.create(new Product(0, UUID.randomUUID().toString(), 10000, 5, false));
    }
    private OrderRequest request(Product item) {
        return new OrderRequest(UUID.randomUUID().toString(), item.id(), 1, false, false);
    }

    @Test
    @Tag("smoke")
    @DisplayName("Замовлення зберігається у БД")
    void createOrder() throws Exception {
        var saved = service.placeOrder(request(item()));
        assertEquals(OrderStatus.CONFIRMED, saved.status());
        assertEquals(saved, orders.findById(saved.id()));
    }
    @Test
    @Tag("smoke")
    @DisplayName("Повернення товару змінює статус")
    void returnOrder() throws Exception {
        // Виправлення №1: тест сам готує своє замовлення і не користується станом інших тестів.
        var saved = service.placeOrder(request(item()));
        var returned = service.returnOrder(saved.id());
        assertEquals(OrderStatus.RETURNED, returned.status());
        assertEquals(returned, orders.findById(saved.id()));
    }
    @Test
    @DisplayName("Картка товару зберігає залишок")
    void productStock() throws Exception {
        // Виправлення №2: унікальний sku (UNIQUE у БД) — тест не конфліктує з іншими.
        var saved = product.create(new Product(0, "REGRESSION-" + UUID.randomUUID().toString().substring(0, 8), 10000, 5, false));
        assertEquals(5, product.findById(saved.id()).stock());
    }
    @Test
    @DisplayName("Картка товару зберігає ціну одиниці")
    void productRate() throws Exception {
        var saved = product.create(new Product(0, "REGRESSION-" + UUID.randomUUID().toString().substring(0, 8), 15000, 3, false));
        assertEquals(15000, product.findById(saved.id()).unitPrice());
    }
    @Test
    @DisplayName("Замовлення після зовнішнього дозволу")
    void orderAfterPolicyCheck() throws Exception {
        var item = item();
        var request = request(item);
        var delayed = new OrderService(product, orders, (value, quantity) -> {
            try {
                Thread.sleep(Long.getLong("regression.policyDelayMs", 200L));
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Перевірку перервано", exception);
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var operation = CompletableFuture.supplyAsync(() -> {
                try { return delayed.placeOrder(request); }
                catch (Exception exception) { throw new CompletionException(exception); }
            }, executor);
            // Виправлення №3: замість Thread.sleep(50) (fixed wait) чекаємо САМЕ завершення операції.
            // Таймаут 30 с — лише захист від зависання, а не припущення про швидкість.
            var placed = operation.get(30, TimeUnit.SECONDS);
            var saved = orders.findOrders(item.id(), OrderStatus.CONFIRMED);
            assertEquals(1, saved.size());
            assertEquals(placed, saved.getFirst());
            assertEquals(request.reference(), saved.getFirst().reference());
            assertEquals(10000, saved.getFirst().total());
        }
    }
    @Test
    @DisplayName("Вибірка містить дві замовлення товару")
    void orderSelection() throws Exception {
        var item = item();
        var prefix = UUID.randomUUID().toString().substring(0, 8);
        var references = List.of(prefix + "-A", prefix + "-B");
        var fixtureOrder = Boolean.getBoolean("regression.reverseFixture") ? references.reversed() : references;
        for (var reference : fixtureOrder) service.placeOrder(new OrderRequest(reference, item.id(), 1, false, false));
        // Виправлення №4: без ORDER BY PostgreSQL не гарантує порядок рядків — задаємо його явно.
        var actual = jdbc.queryForList("SELECT r.reference FROM orders r JOIN order_items i ON i.order_id=r.id WHERE i.product_id=? ORDER BY r.reference", String.class, item.id());
        assertEquals(references, actual);
    }
    @Test
    @Tag("smoke")
    @DisplayName("Замовлення зберігає суму й позицію")
    void orderAmount() throws Exception {
        var item = item();
        var saved = service.placeOrder(request(item));
        var stored = orders.findById(saved.id());
        assertEquals(10000, stored.total());
        assertEquals(item.id(), stored.item().productId());
        assertEquals(1, stored.item().quantity());
        assertEquals(10000, stored.item().unitPrice());
    }
    @Test
    @Tag("smoke")
    @DisplayName("Нестача товару не створює замовлення")
    void stockRejection() throws Exception {
        var item = item();
        var request = new OrderRequest(UUID.randomUUID().toString(), item.id(), 6, false, false);
        assertThrows(OrderRejectedException.class, () -> service.placeOrder(request));
        assertTrue(orders.findOrders(item.id(), OrderStatus.CONFIRMED).isEmpty());
    }
    @Test
    @DisplayName("Зовнішня відмова не створює замовлення")
    void policyRejection() throws Exception {
        var item = item();
        var denied = new OrderService(product, orders, (value, quantity) -> false);
        assertThrows(OrderRejectedException.class, () -> denied.placeOrder(request(item)));
        assertTrue(orders.findOrders(item.id(), OrderStatus.CONFIRMED).isEmpty());
    }
    @Test
    @DisplayName("Повторне повернення не змінює замовлення")
    void repeatedReturn() throws Exception {
        var saved = service.placeOrder(request(item()));
        var returned = service.returnOrder(saved.id());
        assertThrows(OrderRejectedException.class, () -> service.returnOrder(saved.id()));
        assertEquals(returned, orders.findById(saved.id()));
    }
}