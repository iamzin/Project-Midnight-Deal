package com.midnight.deal.booking;

import com.midnight.deal.stock.StockReservationService;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingConcurrencyE2ETest extends AbstractIntegrationTest {
    @LocalServerPort int port;
    @Autowired StockReservationService reservation;

    @BeforeEach void init(){ reservation.initStock(1L, 10); }

    @Test
    void only_10_paid_under_concurrent_http() throws Exception {
        RestClient client = RestClient.create();
        int users = 300;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger paid = new AtomicInteger();
        for (int u = 500; u < 500 + users; u++) {
            final long uid = u;
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException ignored) {}
                String body = "{\"productId\":1,\"userId\":"+uid+",\"totalAmount\":100000,"
                    + "\"payments\":[{\"method\":\"CREDIT_CARD\",\"amount\":90000},"
                    + "{\"method\":\"Y_POINT\",\"amount\":10000}]}";
                try {
                    ResponseEntity<String> r = client.post()
                        .uri("http://localhost:"+port+"/api/bookings")
                        .header("Idempotency-Key","e2e-"+uid)
                        .contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toEntity(String.class);
                    if (r.getStatusCode().is2xxSuccessful()) paid.incrementAndGet();
                } catch (Exception ignored) { /* 409/402 정상 거부 */ }
            });
        }
        go.countDown(); pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS);
        assertThat(paid.get()).isEqualTo(10); // 초과판매 0
    }
}
