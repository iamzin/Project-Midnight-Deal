package com.midnight.deal.payment;

import com.midnight.deal.payment.gateway.MockPaymentGateway;
import com.midnight.deal.payment.gateway.PgResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockPaymentGatewayTest {
    MockPaymentGateway gw = new MockPaymentGateway();

    @Test
    void success_returns_tx() {
        PgResult r = gw.charge(PaymentMethod.CREDIT_CARD, 1L, 1000);
        assertThat(r.success()).isTrue();
        assertThat(r.txId()).isNotBlank();
    }

    @Test
    void over_limit_amount_fails() {
        // 9,000,000 초과 금액 = 한도초과 시뮬레이션
        PgResult r = gw.charge(PaymentMethod.CREDIT_CARD, 1L, 9_999_999);
        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).isEqualTo("LIMIT_EXCEEDED");
    }
}
