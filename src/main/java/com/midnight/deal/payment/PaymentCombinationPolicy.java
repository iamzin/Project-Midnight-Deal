package com.midnight.deal.payment;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PaymentCombinationPolicy {
    public void validate(List<PaymentLine> lines) {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("EMPTY_PAYMENT");
        Set<PaymentMethod> methods = lines.stream().map(PaymentLine::method).collect(Collectors.toSet());
        // 신용카드 + Y페이 혼용 불가
        if (methods.contains(PaymentMethod.CREDIT_CARD) && methods.contains(PaymentMethod.Y_PAY))
            throw new IllegalStateException("CARD_YPAY_NOT_ALLOWED");
        // 동일 수단 중복 라인 금지(합계는 단일 라인으로)
        if (methods.size() != lines.size())
            throw new IllegalStateException("DUPLICATE_METHOD");
    }
}
