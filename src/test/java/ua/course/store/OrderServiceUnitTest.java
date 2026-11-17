package ua.course.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceUnitTest {
    private final OrderService service = new OrderService(
            Mockito.mock(ProductRepository.class), Mockito.mock(OrderRepository.class), (p, q) -> true);

    @Test @DisplayName("Сума нижче порогу: без знижки")
    void noDiscountBelowThreshold() {
        assertEquals(50000, service.calculateTotal(10000, 5, false, false, false));
    }
    @Test @DisplayName("Поріг 1400 грн включно: знижка 12%")
    void discountAtThreshold() {
        assertEquals(123200, service.calculateTotal(70000, 2, false, false, false));
    }
    @Test @DisplayName("Акційний товар: знижки немає")
    void noDiscountOnSale() {
        assertEquals(140000, service.calculateTotal(70000, 2, false, false, true));
    }
    @Test @DisplayName("Одразу під порогом: без знижки")
    void justBelowThreshold() {
        assertEquals(139999, service.calculateTotal(139999, 1, false, false, false));
    }
    @Test @DisplayName("Некоректна кількість і ціна")
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10000, 0));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10000, 21));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(99, 1));
    }
}