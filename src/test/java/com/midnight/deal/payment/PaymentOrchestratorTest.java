package com.midnight.deal.payment;

import com.midnight.deal.point.UserPointRepository;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PaymentOrchestratorTest extends AbstractIntegrationTest {
    @Autowired PaymentOrchestrator orchestrator;
    @Autowired UserPointRepository pointRepo;

    @Test
    void composite_success_records_details() {
        PaymentOutcome out = orchestrator.pay(new PaymentContext(0L, 100L, 10000,
            List.of(new PaymentLine(PaymentMethod.CREDIT_CARD, 9000),
                    new PaymentLine(PaymentMethod.Y_POINT, 1000))));
        assertThat(out.success()).isTrue();
        assertThat(out.results()).hasSize(2);
        assertThat(out.results()).allMatch(r -> r.success());
    }

    @Test
    void point_success_but_card_over_limit_refunds_point() {
        long before = pointRepo.findByUserId(101L).orElseThrow().getBalance();
        PaymentOutcome out = orchestrator.pay(new PaymentContext(0L, 101L, 9_000_001 + 1000,
            List.of(new PaymentLine(PaymentMethod.Y_POINT, 1000),
                    new PaymentLine(PaymentMethod.CREDIT_CARD, 9_000_001)))); // 카드 한도초과
        assertThat(out.success()).isFalse();
        // 포인트 차감분 환불 확인
        assertThat(pointRepo.findByUserId(101L).orElseThrow().getBalance()).isEqualTo(before);
    }
}
