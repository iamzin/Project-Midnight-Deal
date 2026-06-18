package com.midnight.deal.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midnight.deal.point.UserPointRepository;
import com.midnight.deal.stock.ReserveResult;
import com.midnight.deal.stock.StockReservationService;
import com.midnight.deal.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BookingFailureCompensationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired StockReservationService reservation;
    @Autowired UserPointRepository pointRepo;

    @BeforeEach void init(){ reservation.initStock(1L, 10); }

    /**
     * CREDIT_CARD 금액을 9_000_001로 설정: MockPaymentGateway는 amount > 9_000_000 시 LIMIT_EXCEEDED 반환.
     * 9_000_000은 성공하므로 반드시 9_000_001을 사용해야 PAYMENT_FAILED가 발생한다.
     * totalAmount = 9_000_001 + 1000 = 9_001_001 (AMOUNT_MISMATCH 방지).
     *
     * 검증 포인트:
     * 1. POST /api/bookings → HTTP 402 + body code "PAYMENT_FAILED"
     * 2. 포인트 잔액 복원: Orchestrator의 compensate()가 Y_POINT 환불 처리
     * 3. Redis 재고 복원: BookingService catch에서 reservation.release() 호출 → buyers SREM + stock INCR
     *    → 동일 유저의 재시도가 RESERVED 반환 (ALREADY_PURCHASED가 아님)
     */
    @Test
    void card_over_limit_restores_stock_point_and_lock() throws Exception {
        long userId = 400L;
        long before = pointRepo.findByUserId(userId).orElseThrow().getBalance();

        // 카드 9_000_001: MockPaymentGateway 한도초과(> 9_000_000) → LIMIT_EXCEEDED → PAYMENT_FAILED
        // totalAmount = 9_001_001 (1000 + 9_000_001) → AMOUNT_MISMATCH 없음
        String body = om.writeValueAsString(java.util.Map.of(
            "productId", 1, "userId", userId, "totalAmount", 9_001_001,
            "payments", java.util.List.of(
                java.util.Map.of("method", "Y_POINT",      "amount", 1000),
                java.util.Map.of("method", "CREDIT_CARD",  "amount", 9_000_001))));

        mvc.perform(post("/api/bookings").header("Idempotency-Key", "fail-400")
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isPaymentRequired())
           .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));

        // 포인트 복원: 차감 후 환불되어 before와 동일해야 함
        long after = pointRepo.findByUserId(userId).orElseThrow().getBalance();
        assertThat(after)
            .as("포인트 잔액이 결제 실패 후 복원되어야 함 (before=%d, after=%d)", before, after)
            .isEqualTo(before);

        // 재고 복원: 실패한 유저가 buyers set에서 제거되어 재시도 시 RESERVED 반환
        ReserveResult retryResult = reservation.reserve(1L, userId);
        assertThat(retryResult)
            .as("결제 실패 후 같은 유저가 재시도 시 RESERVED여야 함 (실제: %s)", retryResult)
            .isEqualTo(ReserveResult.RESERVED);
    }
}
