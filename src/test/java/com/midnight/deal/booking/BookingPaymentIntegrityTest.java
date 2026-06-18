package com.midnight.deal.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midnight.deal.payment.Payment;
import com.midnight.deal.payment.PaymentDetail;
import com.midnight.deal.payment.PaymentRepository;
import com.midnight.deal.payment.gateway.MockPaymentGateway;
import com.midnight.deal.stock.StockReservationService;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * no-orphan-charge 불변식 + payment 영속을 증명하는 통합 테스트.
 *
 * 핵심 불변식: PG에 청구가 일어났다면 반드시 PAID 주문 + 영속된 Payment 레코드가 존재한다.
 *   - confirm()이 모든 DB 자원(주문/재고 backstop/1인1구매 락)을 청구 '전에' 선점·검증하므로,
 *     청구 이후 실패할 DB 단계가 없다 → 청구됐는데 주문이 롤백되는 orphan charge가 불가능.
 *   - 거절 경로(ALREADY_PURCHASED)는 Redis reserve()에서 confirm()/pay() 진입 전에 차단되므로
 *     성공 청구가 추가로 발생하지 않는다.
 */
@AutoConfigureMockMvc
class BookingPaymentIntegrityTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired StockReservationService reservation;
    @Autowired PaymentRepository paymentRepo;
    @Autowired BookingOrderRepository orderRepo;
    @Autowired MockPaymentGateway gateway;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach void init() {
        reservation.initStock(1L, 10);
        gateway.reset(); // 결정적 베이스라인
    }

    private String body(long userId) throws Exception {
        return om.writeValueAsString(java.util.Map.of(
            "productId", 1, "userId", userId, "totalAmount", 100000,
            "payments", java.util.List.of(
                java.util.Map.of("method", "CREDIT_CARD", "amount", 90000),
                java.util.Map.of("method", "Y_POINT", "amount", 10000))));
    }

    /**
     * 정상 예약(card+point) → Payment 1건 영속, details 합 = totalAmount,
     * 성공 카드 청구 1회(Y_POINT는 PointService 경유라 게이트웨이 미타). 모든 디테일은 COMPLETED.
     */
    @Test
    void successful_booking_persists_payment_with_details() throws Exception {
        mvc.perform(post("/api/bookings").header("Idempotency-Key", "pi-700")
                .contentType(MediaType.APPLICATION_JSON).content(body(700)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("PAID"));

        // DB 주문 상태가 실제로 PAID로 영속됐는지 검증 (응답 body가 아니라 booking_order.status).
        // 회귀 방지: bulk UPDATE의 clearAutomatically가 order를 detach 하면 markPaid가 반영되지 않아
        // PENDING으로 남는 버그를 잡는다.
        assertThat(orderRepo.findByIdempotencyKey("pi-700"))
            .as("주문이 존재하고 status=PAID로 영속").get()
            .extracting(BookingOrder::getStatus).isEqualTo(OrderStatus.PAID);

        // Payment 영속 검증 — LAZY details 접근은 트랜잭션 내에서 수행(open-in-view=false)
        assertThat(paymentRepo.count()).as("성공 예약 후 Payment 1건 영속").isEqualTo(1);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Payment p = paymentRepo.findAll().get(0);
            assertThat(p.getStatus()).isEqualTo("COMPLETED");
            assertThat(p.getTotalAmount()).isEqualTo(100000);
            assertThat(p.getDetails()).hasSize(2);
            assertThat(p.getDetails().stream().mapToLong(PaymentDetail::getAmount).sum())
                .as("PaymentDetail 합계 = totalAmount").isEqualTo(100000);
            assertThat(p.getDetails()).allMatch(d -> "COMPLETED".equals(d.getStatus()));
            // 카드 디테일은 PG txId 보유(정산/대사 키)
            assertThat(p.getDetails()).anyMatch(d -> d.getPgTxId() != null && !d.getPgTxId().isBlank());
        });

        // 게이트웨이: 성공 카드 청구 정확히 1회, 취소 0회
        assertThat(gateway.getSuccessfulChargeCount()).as("성공 카드 청구 1회").isEqualTo(1);
        assertThat(gateway.getCancelCount()).as("정상 흐름이므로 PG 취소 없음").isEqualTo(0);
    }

    /**
     * 같은 유저의 2차 예약(다른 키) → 409 ALREADY_PURCHASED (Redis 경로에서 차단).
     * 거절된 시도는 confirm()/pay()에 진입하지 않으므로:
     *   - 새 Payment 행이 추가되지 않고(count 그대로 1),
     *   - 게이트웨이 청구 카운트가 늘지 않는다(성공 청구는 1차 그대로, 추가 청구·취소 0).
     * → orphan charge가 발생할 수 없음을 결정적으로 증명.
     */
    @Test
    void rejected_duplicate_does_not_charge_or_persist() throws Exception {
        long userId = 701L;

        // 1차: 정상 구매 → Payment 1건, 성공 청구 1회
        mvc.perform(post("/api/bookings").header("Idempotency-Key", "pi-701a")
                .contentType(MediaType.APPLICATION_JSON).content(body(userId)))
           .andExpect(status().isOk());
        assertThat(paymentRepo.count()).isEqualTo(1);
        int chargesAfterFirst = gateway.getChargeCount();
        int successAfterFirst = gateway.getSuccessfulChargeCount();
        assertThat(successAfterFirst).isEqualTo(1);

        // 2차: 같은 유저, 다른 키 → Redis reserve()에서 ALREADY_PURCHASED 차단
        mvc.perform(post("/api/bookings").header("Idempotency-Key", "pi-701b")
                .contentType(MediaType.APPLICATION_JSON).content(body(userId)))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_PURCHASED"));

        // 거절 시도는 결제·영속 어느 것도 건드리지 않는다
        assertThat(paymentRepo.count()).as("거절 시도는 새 Payment를 영속하지 않음").isEqualTo(1);
        assertThat(gateway.getChargeCount())
            .as("reserve()가 confirm/pay 전에 차단 → 추가 청구 시도 없음").isEqualTo(chargesAfterFirst);
        assertThat(gateway.getSuccessfulChargeCount())
            .as("성공 청구는 1차 1건 그대로").isEqualTo(successAfterFirst);
        assertThat(gateway.getCancelCount()).as("거절 경로에 PG 취소 없음").isEqualTo(0);
    }
}
