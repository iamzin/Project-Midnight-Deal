package com.midnight.deal.point;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class PointServiceTest extends AbstractIntegrationTest {
    @Autowired PointService service;
    @Autowired UserPointRepository repo;

    @Test
    void use_then_refund_restores_balance() {
        long before = repo.findByUserId(10L).orElseThrow().getBalance();
        service.use(10L, 1L, 3000);
        assertThat(repo.findByUserId(10L).orElseThrow().getBalance()).isEqualTo(before - 3000);
        service.refund(10L, 1L, 3000);
        assertThat(repo.findByUserId(10L).orElseThrow().getBalance()).isEqualTo(before);
    }

    @Test
    void use_over_balance_throws() {
        assertThatThrownBy(() -> service.use(11L, 1L, 999_999))
            .hasMessageContaining("INSUFFICIENT_POINT");
    }
}
