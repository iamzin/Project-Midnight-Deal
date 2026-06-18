package com.midnight.deal.stock;

import com.midnight.deal.common.BusinessException;
import com.midnight.deal.common.ErrorCode;
import com.midnight.deal.product.ProductOpenService;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductGateTest extends AbstractIntegrationTest {

    @Autowired ProductGate gate;
    @Autowired ProductOpenService openService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void closed_product_is_rejected() {
        gate.publishClosed(1L);
        assertThatThrownBy(() -> gate.ensureOpen(1L))
            .isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo(ErrorCode.NOT_OPEN);
    }

    @Test
    void admin_open_makes_product_bookable_and_persists() {
        gate.publishClosed(1L);                       // 닫힌 상태에서 출발
        assertThatThrownBy(() -> gate.ensureOpen(1L)).isInstanceOf(BusinessException.class);

        openService.open(1L);                          // 임의 오픈

        gate.ensureOpen(1L);                           // 더 이상 예외 없음 → 통과
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM product WHERE id = 1", String.class);
        Object openAt = jdbcTemplate.queryForObject(
            "SELECT open_at FROM product WHERE id = 1", Object.class);
        assertThat(status).isEqualTo("OPEN");
        assertThat(openAt).isNotNull();
    }

    @Test
    void opening_unknown_product_reports_not_found() {
        assertThatThrownBy(() -> openService.open(999L))
            .isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }
}
