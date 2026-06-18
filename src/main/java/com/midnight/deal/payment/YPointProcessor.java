package com.midnight.deal.payment;

import com.midnight.deal.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class YPointProcessor implements PaymentProcessor {
    private final PointService pointService;
    public boolean supports(PaymentMethod m){ return m == PaymentMethod.Y_POINT; }
    public PaymentResult process(PaymentCommand c){
        try {
            pointService.use(c.userId(), c.orderId(), c.amount());
            return new PaymentResult(true, PaymentMethod.Y_POINT, c.amount(), "POINT-"+c.orderId(), null);
        } catch (IllegalStateException e) {
            String reason = "INSUFFICIENT_POINT".equals(e.getMessage()) ? "INSUFFICIENT_POINT" : e.getMessage();
            return new PaymentResult(false, PaymentMethod.Y_POINT, c.amount(), null, reason);
        }
    }
    public void cancel(PaymentResult r){ /* 포인트 환불은 Orchestrator가 PointService.refund로 보상 */ }
}
