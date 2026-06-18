package com.midnight.deal.booking;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class IdempotencyServiceTest extends AbstractIntegrationTest {
    @Autowired IdempotencyService service;

    @Test
    void first_begin_true_second_false() {
        String key = "idem-" + System.nanoTime();
        assertThat(service.tryBegin(key)).isTrue();
        assertThat(service.tryBegin(key)).isFalse();
    }

    @Test
    void save_and_find_result() {
        String key = "idem-" + System.nanoTime();
        service.tryBegin(key);
        service.saveResult(key, "{\"orderId\":5}");
        assertThat(service.findResult(key)).contains("{\"orderId\":5}");
    }
}
