package com.midnight.deal.booking;

import com.midnight.deal.booking.dto.BookingRequest;
import com.midnight.deal.booking.dto.BookingResponse;
import com.midnight.deal.common.BusinessException;
import com.midnight.deal.common.ErrorCode;
import com.midnight.deal.payment.*;
import com.midnight.deal.stock.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingConfirmService {
    private final StockRepository stockRepo;
    private final PaymentOrchestrator orchestrator;
    private final BookingOrderRepository orderRepo;
    private final PurchaseLockRepository lockRepo;
    private final PaymentRepository paymentRepo;

    /**
     * 결제 + 영속 + DB backstop을 단일 트랜잭션으로 처리한다.
     * 실패 시 전체 롤백되어 주문 INSERT/재고 backstop/구매락이 모두 원복된다.
     * 별도 빈이므로 프록시를 거쳐 @Transactional이 실제 적용된다(self-invocation 함정 회피).
     *
     * 순서 불변식(C1+C2 — 청구 후 DB거절로 인한 orphan charge 제거):
     *   모든 DB 자원(주문 INSERT, 재고 backstop, 1인1구매 락)을 외부 결제(orchestrator.pay)보다
     *   '먼저' 선점·검증한다. 따라서 PG 청구는 트랜잭션 내 마지막 실패 가능 외부 단계이며,
     *   청구 이후에는 더 이상 실패할 DB 단계가 없다 → 청구됐는데 주문이 롤백되는 경우가 발생할 수 없다.
     *   - confirmSold: 조건부 UPDATE(WHERE sold_qty<total_qty)가 즉시 실행되어 초과판매면 청구 전 차단.
     *   - lockRepo.saveAndFlush: PK 충돌(1인1구매)을 청구 전에 동기적으로 surface (save가 아니라 flush 강제).
     *   pay()가 실패하면 orchestrator가 이미 포인트 환불을 보상했고, @Transactional 롤백이
     *   주문/재고 backstop/락을 원복한다. Redis 선점은 BookingService catch에서 release로 보상한다.
     */
    @Transactional
    public BookingResponse confirm(String idemKey, BookingRequest req) {
        // 1) 주문 INSERT (PENDING) — id 확보
        BookingOrder order = orderRepo.save(
            new BookingOrder(req.productId(), req.userId(), idemKey, req.totalAmount()));

        // 2) DB 재고 backstop (조건부 UPDATE) — 영향행 0이면 초과판매 → 청구 전 롤백
        if (stockRepo.confirmSold(req.productId()) == 0)
            throw new BusinessException(ErrorCode.SOLD_OUT);

        // 3) 1인 1구매 DB backstop (PK = userId+productId) — saveAndFlush로 PK 충돌을 청구 전에 동기 발생
        lockRepo.saveAndFlush(new PurchaseLock(req.userId(), req.productId(), order.getId()));

        // 4) 외부 결제 — 마지막 실패 가능 외부 단계 (이후 DB 거절 없음 → orphan charge 불가)
        List<PaymentLine> lines = req.payments().stream()
            .map(l -> new PaymentLine(l.method(), l.amount())).toList();
        PaymentOutcome outcome = orchestrator.pay(
            new PaymentContext(order.getId(), req.userId(), req.totalAmount(), lines));

        if (!outcome.success())
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, outcome.failureReason());

        // 5) Payment + PaymentDetail 영속 (동일 트랜잭션, pg_tx_id 기록 — 정산/대사용 durable 레코드)
        Payment payment = new Payment(order.getId(), req.totalAmount());
        for (PaymentResult r : outcome.results())
            payment.addDetail(new PaymentDetail(r.method(), r.amount(), r.pgTxId(), "COMPLETED"));
        payment.complete();
        paymentRepo.save(payment);

        // 6) 주문 PAID 확정
        order.markPaid();
        return new BookingResponse(order.getId(), "PAID", "예약 완료");
    }
}
