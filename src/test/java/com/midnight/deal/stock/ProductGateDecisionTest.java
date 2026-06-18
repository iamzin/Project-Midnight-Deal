package com.midnight.deal.stock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 순수 판정 로직 단위 테스트 (Redis/Spring 불필요). */
class ProductGateDecisionTest {

    private static final long NOW = 1_000_000L;

    @Test void null_value_is_closed() {
        assertThat(ProductGate.isOpen(null, NOW)).isFalse();
    }

    @Test void closed_marker_is_closed() {
        assertThat(ProductGate.isOpen("CLOSED", NOW)).isFalse();
    }

    @Test void future_open_epoch_is_not_open_yet() {
        assertThat(ProductGate.isOpen(String.valueOf(NOW + 1), NOW)).isFalse();
    }

    @Test void past_open_epoch_is_open() {
        assertThat(ProductGate.isOpen(String.valueOf(NOW - 1), NOW)).isTrue();
    }

    @Test void exact_open_epoch_is_open() {
        assertThat(ProductGate.isOpen(String.valueOf(NOW), NOW)).isTrue();
    }

    @Test void garbage_value_is_closed() {
        assertThat(ProductGate.isOpen("not-a-number", NOW)).isFalse();
    }
}
