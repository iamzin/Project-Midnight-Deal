package com.midnight.deal.payment.gateway;

import com.midnight.deal.payment.PaymentMethod;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockPaymentGateway implements PaymentGateway {
    private final AtomicLong seq = new AtomicLong();
    private static final long LIMIT = 9_000_000;

    // 테스트 가시성용 카운터 — orphan charge 불변식 검증에 사용
    private final AtomicInteger chargeCount = new AtomicInteger();           // charge() 진입 횟수
    private final AtomicInteger successfulChargeCount = new AtomicInteger();  // 성공 청구 횟수
    private final AtomicInteger cancelCount = new AtomicInteger();           // PG 취소 호출 횟수

    @Override
    public PgResult charge(PaymentMethod method, long userId, long amount) {
        chargeCount.incrementAndGet();
        if (amount > LIMIT) return PgResult.fail("LIMIT_EXCEEDED");
        if (amount <= 0)    return PgResult.fail("INVALID_AMOUNT");
        // 실제 PG 호출 자리 — 인터페이스로만 흐름 유지
        successfulChargeCount.incrementAndGet();
        return PgResult.ok(method + "-" + seq.incrementAndGet());
    }

    @Override
    public void cancel(String txId) {
        cancelCount.incrementAndGet();
        /* PG 취소 호출 자리 (Mock no-op) */
    }

    public int getChargeCount()           { return chargeCount.get(); }
    public int getSuccessfulChargeCount() { return successfulChargeCount.get(); }
    public int getCancelCount()           { return cancelCount.get(); }

    /** 테스트 @BeforeEach 베이스라인 리셋용 (seq는 보존 — txId 유니크성 유지). */
    public void reset() {
        chargeCount.set(0);
        successfulChargeCount.set(0);
        cancelCount.set(0);
    }
}
