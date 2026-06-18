package com.midnight.deal.stock;

import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class StockReservationConcurrencyTest extends AbstractIntegrationTest {
    @Autowired StockReservationService service;

    @BeforeEach void init(){ service.initStock(1L, 10); }

    @Test
    void only_10_reserved_among_many_distinct_users() throws Exception {
        int users = 500;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch ready = new CountDownLatch(users), go = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        for (int u = 1; u <= users; u++) {
            final long uid = u;
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                if (service.reserve(1L, uid) == ReserveResult.RESERVED) reserved.incrementAndGet();
            });
        }
        ready.await(); go.countDown(); pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(reserved.get()).isEqualTo(10); // 초과판매 0, 미달 0
    }

    @Test
    void same_user_reserves_at_most_once() throws Exception {
        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException ignored) {}
                if (service.reserve(1L, 7L) == ReserveResult.RESERVED) reserved.incrementAndGet();
            });
        }
        go.countDown(); pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(reserved.get()).isEqualTo(1); // 같은 유저는 1회만
    }
}
