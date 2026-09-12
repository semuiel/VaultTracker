package ru.vaulttracker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ItemAmountTest {
    @Test void exactAndPartialShulkersKeepExactTotal() {
        assertEquals("1727 шт.",ItemAmount.format(1727,64));
        assertEquals("1728 шт. (1 шалкер)",ItemAmount.format(1728,64));
        assertEquals("2000 шт. (1 шалкер + 272 шт.)",ItemAmount.format(2000,64));
        assertEquals("3456 шт. (2 шалкера)",ItemAmount.format(3456,64));
    }
    @Test void pearlsAndUnstackableBooksUseTheirStackSize() {
        assertEquals("433 шт. (1 шалкер + 1 шт.)",ItemAmount.format(433,16));
        assertEquals("28 шт. (1 шалкер + 1 шт.)",ItemAmount.format(28,1));
        assertEquals("0 шт.",ItemAmount.format(0,64));
        assertEquals("594 шт. (22 шалкера)",ItemAmount.format(594,1));
        assertEquals("297 шт. (11 шалкеров)",ItemAmount.format(297,1));
    }
}
