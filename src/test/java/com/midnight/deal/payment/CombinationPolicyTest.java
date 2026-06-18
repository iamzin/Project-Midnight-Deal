package com.midnight.deal.payment;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CombinationPolicyTest {
    PaymentCombinationPolicy policy = new PaymentCombinationPolicy();

    @Test void card_plus_point_ok() {
        assertThatCode(() -> policy.validate(List.of(
            new PaymentLine(PaymentMethod.CREDIT_CARD, 9000),
            new PaymentLine(PaymentMethod.Y_POINT, 1000)))).doesNotThrowAnyException();
    }
    @Test void ypay_plus_point_ok() {
        assertThatCode(() -> policy.validate(List.of(
            new PaymentLine(PaymentMethod.Y_PAY, 9000),
            new PaymentLine(PaymentMethod.Y_POINT, 1000)))).doesNotThrowAnyException();
    }
    @Test void card_plus_ypay_rejected() {
        assertThatThrownBy(() -> policy.validate(List.of(
            new PaymentLine(PaymentMethod.CREDIT_CARD, 5000),
            new PaymentLine(PaymentMethod.Y_PAY, 5000))))
            .hasMessageContaining("CARD_YPAY_NOT_ALLOWED");
    }
    @Test void empty_payment_rejected() {
        assertThatThrownBy(() -> policy.validate(List.of()))
            .hasMessageContaining("EMPTY_PAYMENT");
    }
    @Test void duplicate_method_rejected() {
        assertThatThrownBy(() -> policy.validate(List.of(
            new PaymentLine(PaymentMethod.CREDIT_CARD, 5000),
            new PaymentLine(PaymentMethod.CREDIT_CARD, 5000))))
            .hasMessageContaining("DUPLICATE_METHOD");
    }
}
