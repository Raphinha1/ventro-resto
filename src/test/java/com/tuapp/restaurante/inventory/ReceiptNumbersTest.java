package com.tuapp.restaurante.inventory;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptNumbersTest {
    @Test
    void exactPersistentFormatsPreserveValueAndRejectRounding() {
        assertThat(ReceiptNumbers.quantity(new BigDecimal("1.234567"))).isEqualTo(new BigDecimal("1.234567"));
        assertThat(ReceiptNumbers.factor(new BigDecimal("24"))).isEqualTo(new BigDecimal("24.000000000000"));
        assertThat(ReceiptNumbers.cost(new BigDecimal("1.2300"))).isEqualTo(new BigDecimal("1.23"));
        assertThatThrownBy(() -> ReceiptNumbers.quantity(new BigDecimal("1.2345671"))).hasMessageContaining("exactamente");
        assertThatThrownBy(() -> ReceiptNumbers.factor(new BigDecimal("1.0000000000001"))).hasMessageContaining("exactamente");
        assertThatThrownBy(() -> ReceiptNumbers.cost(new BigDecimal("1.235"))).hasMessageContaining("exactamente");
    }

    @Test
    void rangesAndPositivityAreEnforcedForAllFormats() {
        for (BigDecimal value : new BigDecimal[] {null, BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            assertThatThrownBy(() -> ReceiptNumbers.quantity(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReceiptNumbers.factor(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ReceiptNumbers.cost(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ReceiptNumbers.quantity(new BigDecimal("9999999999999.999999"))).isEqualByComparingTo("9999999999999.999999");
        assertThat(ReceiptNumbers.factor(new BigDecimal("999999999999.999999999999"))).isEqualByComparingTo("999999999999.999999999999");
        assertThat(ReceiptNumbers.cost(new BigDecimal("99999999999999999.99"))).isEqualByComparingTo("99999999999999999.99");
        assertThatThrownBy(() -> ReceiptNumbers.quantity(new BigDecimal("10000000000000"))).hasMessageContaining("excede");
        assertThatThrownBy(() -> ReceiptNumbers.factor(new BigDecimal("1000000000000"))).hasMessageContaining("excede");
        assertThatThrownBy(() -> ReceiptNumbers.cost(new BigDecimal("100000000000000000"))).hasMessageContaining("excede");
    }
}
