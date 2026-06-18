package com.midnight.deal.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midnight.deal.stock.StockReservationService;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BookingApiTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired StockReservationService reservation;
    @Autowired StringRedisTemplate redis;

    @BeforeEach void init(){ reservation.initStock(1L, 10); }

    private String body(long userId) throws Exception {
        return om.writeValueAsString(java.util.Map.of(
            "productId", 1, "userId", userId, "totalAmount", 100000,
            "payments", java.util.List.of(
                java.util.Map.of("method","CREDIT_CARD","amount",90000),
                java.util.Map.of("method","Y_POINT","amount",10000))));
    }

    @Test
    void booking_success() throws Exception {
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-200")
                .contentType(MediaType.APPLICATION_JSON).content(body(200)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void same_idem_key_returns_same_result() throws Exception {
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-201")
                .contentType(MediaType.APPLICATION_JSON).content(body(201)))
           .andExpect(status().isOk());
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-201")
                .contentType(MediaType.APPLICATION_JSON).content(body(201)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("PAID")); // 결제 1회·동일 응답
    }

    @Test
    void same_user_second_order_rejected() throws Exception {
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-202a")
                .contentType(MediaType.APPLICATION_JSON).content(body(202)))
           .andExpect(status().isOk());
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-202b") // 다른 키, 같은 유저
                .contentType(MediaType.APPLICATION_JSON).content(body(202)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_PURCHASED"));
    }

    /**
     * 보상 버그 가드: ALREADY_PURCHASED 거절은 이 요청이 선점한 것이 아니므로 release()를 호출하면 안 된다.
     * 잘못 release하면 직전 구매자가 buyers set에서 SREM되고 Redis stock이 INCR되어 재고가 부풀려진다.
     * 검증: (1) 같은 유저의 2차 시도가 거절된 뒤에도 Redis 재고가 부풀려지지 않고(9 유지),
     *       (2) 다른 신규 유저는 정상 예약 가능하며, (3) 총 PAID는 정확히 2건(재고 한도 내).
     */
    @Test
    void already_purchased_rejection_does_not_inflate_stock() throws Exception {
        // user 300 정상 구매 → Redis stock 10 - 1 = 9
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-300a")
                .contentType(MediaType.APPLICATION_JSON).content(body(300)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(stockRemaining()).isEqualTo(9);

        // 같은 user 300의 2차 시도 → ALREADY_PURCHASED 거절. release 호출되면 stock이 10으로 부풀려짐(버그).
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-300b")
                .contentType(MediaType.APPLICATION_JSON).content(body(300)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_PURCHASED"));
        assertThat(stockRemaining()).isEqualTo(9); // 버그면 10이 됨 — 가드 핵심

        // 신규 user 301은 여전히 정상 예약 가능 → 9 - 1 = 8
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-301")
                .contentType(MediaType.APPLICATION_JSON).content(body(301)))
           .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(stockRemaining()).isEqualTo(8);
    }

    @Test
    void closed_product_is_rejected_with_not_open() throws Exception {
        productGate.publishClosed(1L); // 베이스에서 오픈된 상태를 닫힘으로 override
        mvc.perform(post("/api/bookings").header("Idempotency-Key","k-closed")
                .contentType(MediaType.APPLICATION_JSON).content(body(250)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("NOT_OPEN"));
    }

    private long stockRemaining() {
        String v = redis.opsForValue().get("stock:1");
        return v == null ? -1 : Long.parseLong(v);
    }
}
