package com.midnight.deal.payment;

import com.midnight.deal.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList; import java.util.List;

@Service @RequiredArgsConstructor
public class PaymentOrchestrator {
    private final List<PaymentProcessor> processors;
    private final PaymentCombinationPolicy policy;
    private final PointService pointService;

    public PaymentOutcome pay(PaymentContext ctx) {
        policy.validate(ctx.lines());
        long sum = ctx.lines().stream().mapToLong(PaymentLine::amount).sum();
        if (sum != ctx.totalAmount())
            return new PaymentOutcome(false, "AMOUNT_MISMATCH", List.of());

        List<PaymentResult> done = new ArrayList<>();
        for (PaymentLine line : ctx.lines()) {
            PaymentProcessor proc = processors.stream()
                .filter(p -> p.supports(line.method())).findFirst()
                .orElseThrow(() -> new IllegalStateException("NO_PROCESSOR:" + line.method()));
            PaymentResult r = proc.process(new PaymentCommand(line.method(), ctx.userId(), ctx.orderId(), line.amount()));
            if (!r.success()) {
                compensate(ctx, done);                       // 부분 실패 → 성공분 전부 롤백
                return new PaymentOutcome(false, r.failureReason(), done);
            }
            done.add(r);
        }
        return new PaymentOutcome(true, null, done);
    }

    private void compensate(PaymentContext ctx, List<PaymentResult> done) {
        for (PaymentResult r : done) {
            if (r.method() == PaymentMethod.Y_POINT) {
                pointService.refund(ctx.userId(), ctx.orderId(), r.amount());
            } else {
                processors.stream().filter(p -> p.supports(r.method())).findFirst()
                    .ifPresent(p -> p.cancel(r));            // 카드/Y페이 PG 취소
            }
        }
    }
}
